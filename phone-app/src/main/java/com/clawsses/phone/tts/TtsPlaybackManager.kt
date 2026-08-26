package com.clawsses.phone.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class TtsPlaybackState {
    IDLE,
    SYNTHESIZING,
    PLAYING,
    ERROR,
}

/** Provider-aware TTS playback with stop/replay and stale-request suppression. */
class TtsPlaybackManager(
    private val context: Context,
    private val elevenLabsClient: ElevenLabsClient,
    private val openAiClient: OpenAiTtsClient,
    private val settings: TtsSettingsManager,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val generation = AtomicLong(0)
    private var synthesisJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentTempFile: File? = null
    private val queuedTempFiles = ArrayDeque<File>()
    private var completedSynthesisGeneration = NO_GENERATION
    private var playbackStartedGeneration = NO_GENERATION
    private var requestStartedAtMs = 0L
    private var lastText: String? = null

    private val _state = MutableStateFlow(TtsPlaybackState.IDLE)
    val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

    private val _canReplay = MutableStateFlow(false)
    val canReplay: StateFlow<Boolean> = _canReplay.asStateFlow()

    fun speak(text: String) {
        val normalized = text.trim()
        if (!settings.isEnabled.value || normalized.isEmpty()) return

        val provider = settings.provider.value
        val key = when (provider) {
            TtsProvider.ELEVENLABS -> settings.apiKey.value
            TtsProvider.OPENAI -> settings.openAiApiKey.value
        }
        val voice = settings.selectedVoiceId.value
        if (key.isBlank() || voice.isNullOrBlank()) {
            Log.w(TAG, "TTS provider is not fully configured")
            _state.value = TtsPlaybackState.ERROR
            return
        }

        stopInternal(updateState = false)
        playbackActive.set(true)
        lastText = normalized
        _canReplay.value = true
        _state.value = TtsPlaybackState.SYNTHESIZING
        val requestGeneration = generation.incrementAndGet()
        val speed = settings.speed.value.toDouble()
        completedSynthesisGeneration = NO_GENERATION
        playbackStartedGeneration = NO_GENERATION
        requestStartedAtMs = SystemClock.elapsedRealtime()

        synthesisJob = scope.launch {
            val pendingFiles = mutableListOf<File>()
            try {
                val chunks = splitForSynthesis(normalized)
                Log.i(
                    TAG,
                    "TTS synthesis started: chunks=${chunks.size}, firstChunkChars=${chunks.first().length}",
                )
                chunks.forEachIndexed { index, chunk ->
                    val chunkStartedAtMs = SystemClock.elapsedRealtime()
                    val result = when (provider) {
                        TtsProvider.ELEVENLABS ->
                            elevenLabsClient.synthesize(key, voice, chunk, speed)
                        TtsProvider.OPENAI ->
                            openAiClient.synthesize(key, voice, chunk, speed)
                    }
                    val audioBytes = result.getOrThrow()
                    val tempFile = File.createTempFile("tts_", ".mp3", context.cacheDir)
                    pendingFiles += tempFile
                    FileOutputStream(tempFile).use { output ->
                        output.write(audioBytes)
                    }
                    Log.i(
                        TAG,
                        "TTS chunk synthesized: ${index + 1}/${chunks.size}, " +
                            "chars=${chunk.length}, durationMs=" +
                            (SystemClock.elapsedRealtime() - chunkStartedAtMs),
                    )

                    withContext(Dispatchers.Main) {
                        if (generation.get() == requestGeneration) {
                            queuedTempFiles.addLast(tempFile)
                            pendingFiles.remove(tempFile)
                            if (mediaPlayer == null) playNextAudioFile(requestGeneration)
                        }
                    }
                    if (!isActive || generation.get() != requestGeneration) return@launch
                }

                withContext(Dispatchers.Main) {
                    if (generation.get() == requestGeneration) {
                        completedSynthesisGeneration = requestGeneration
                        if (mediaPlayer == null && queuedTempFiles.isEmpty()) {
                            finishPlayback()
                        }
                    }
                }
            } catch (error: Exception) {
                if (generation.get() == requestGeneration) {
                    Log.e(TAG, "TTS synthesis failed: ${error.javaClass.simpleName}")
                    withContext(Dispatchers.Main) {
                        failPlayback(requestGeneration)
                    }
                }
            } finally {
                pendingFiles.forEach(::deleteTempFile)
            }
        }
    }

    fun replay() {
        lastText?.let(::speak)
    }

    fun stop() {
        stopInternal(updateState = true)
    }

    private fun playNextAudioFile(requestGeneration: Long) {
        if (generation.get() != requestGeneration) return

        releasePlayer()
        deleteCurrentTempFile()
        val file = queuedTempFiles.pollFirst()
        if (file == null) {
            if (completedSynthesisGeneration == requestGeneration) {
                finishPlayback()
            } else {
                _state.value = TtsPlaybackState.SYNTHESIZING
            }
            return
        }

        currentTempFile = file
        try {
            val scoOutput = preferredScoOutput()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (scoOutput != null) {
                                AudioAttributes.USAGE_VOICE_COMMUNICATION
                            } else {
                                AudioAttributes.USAGE_MEDIA
                            }
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setVolume(1f, 1f)
                setOnCompletionListener {
                    releasePlayer()
                    deleteCurrentTempFile()
                    playNextAudioFile(requestGeneration)
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    failPlayback(requestGeneration)
                    true
                }
                prepare()
                scoOutput?.let { output ->
                    val accepted = setPreferredDevice(output)
                    Log.i(
                        TAG,
                        "TTS preferred SCO output: id=${output.id}, accepted=$accepted",
                    )
                }
                start()
            }
            if (playbackStartedGeneration != requestGeneration) {
                playbackStartedGeneration = requestGeneration
                Log.i(
                    TAG,
                    "TTS first audio started: latencyMs=" +
                        (SystemClock.elapsedRealtime() - requestStartedAtMs),
                )
            }
            Log.i(TAG, "TTS playback chunk started: remaining=${queuedTempFiles.size}")
            _state.value = TtsPlaybackState.PLAYING
        } catch (error: Exception) {
            Log.e(TAG, "TTS playback failed: ${error.javaClass.simpleName}")
            failPlayback(requestGeneration)
        }
    }

    private fun finishPlayback() {
        playbackActive.set(false)
        synthesisJob = null
        completedSynthesisGeneration = NO_GENERATION
        playbackStartedGeneration = NO_GENERATION
        _state.value = TtsPlaybackState.IDLE
        Log.i(TAG, "TTS playback completed")
    }

    private fun failPlayback(requestGeneration: Long) {
        if (generation.get() != requestGeneration) return
        generation.incrementAndGet()
        playbackActive.set(false)
        completedSynthesisGeneration = NO_GENERATION
        playbackStartedGeneration = NO_GENERATION
        releasePlayer()
        deleteAllTempFiles()
        _state.value = TtsPlaybackState.ERROR
    }

    private fun preferredScoOutput(): AudioDeviceInfo? {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return null
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        return outputs.firstOrNull {
            it.productName?.toString()?.contains("Glasses", ignoreCase = true) == true
        } ?: outputs.singleOrNull()
    }

    private fun stopInternal(updateState: Boolean) {
        playbackActive.set(false)
        generation.incrementAndGet()
        synthesisJob?.cancel()
        synthesisJob = null
        completedSynthesisGeneration = NO_GENERATION
        playbackStartedGeneration = NO_GENERATION
        releasePlayer()
        deleteAllTempFiles()
        if (updateState) _state.value = TtsPlaybackState.IDLE
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) player.stop()
                player.release()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to release TTS player: ${error.javaClass.simpleName}")
        }
        mediaPlayer = null
    }

    private fun deleteCurrentTempFile() {
        currentTempFile?.let(::deleteTempFile)
        currentTempFile = null
    }

    private fun deleteAllTempFiles() {
        deleteCurrentTempFile()
        while (queuedTempFiles.isNotEmpty()) deleteTempFile(queuedTempFiles.removeFirst())
    }

    private fun deleteTempFile(file: File) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to delete private TTS cache file")
        }
    }

    fun onMessageComplete(text: String) {
        if (settings.isEnabled.value && text.isNotBlank()) speak(text)
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    companion object {
        private const val TAG = "TtsPlaybackManager"
        private const val FIRST_CHUNK_MIN_CHARS = 280
        private const val FIRST_CHUNK_MAX_CHARS = 400
        private const val MAX_CHUNK_CHARS = 1_500
        private const val NO_GENERATION = -1L
        private val playbackActive = AtomicBoolean(false)

        fun isPlaybackActive(): Boolean = playbackActive.get()

        internal fun splitForSynthesis(text: String): List<String> {
            val normalized = text.trim()
            if (normalized.length <= FIRST_CHUNK_MAX_CHARS) return listOf(normalized)
            val chunks = mutableListOf<String>()
            val firstSplitAt = firstChunkSplitIndex(normalized)
            chunks += normalized.take(firstSplitAt + 1).trim()
            var remaining = normalized.drop(firstSplitAt + 1).trimStart()
            while (remaining.isNotEmpty()) {
                if (remaining.length <= MAX_CHUNK_CHARS) {
                    chunks += remaining
                    break
                }
                val window = remaining.take(MAX_CHUNK_CHARS)
                val splitAt = maxOf(
                    window.lastIndexOf(". "),
                    window.lastIndexOf("! "),
                    window.lastIndexOf("? "),
                    window.lastIndexOf("\n"),
                    window.lastIndexOf(" "),
                ).takeIf { it >= MAX_CHUNK_CHARS / 2 } ?: (MAX_CHUNK_CHARS - 1)
                chunks += remaining.take(splitAt + 1).trim()
                remaining = remaining.drop(splitAt + 1).trimStart()
            }
            return chunks.filter { it.isNotEmpty() }
        }

        private fun firstChunkSplitIndex(text: String): Int {
            val window = text.take(FIRST_CHUNK_MAX_CHARS)
            val sentenceBoundary = (FIRST_CHUNK_MIN_CHARS until window.length).firstOrNull { index ->
                val current = window[index]
                current == '\n' ||
                    (current in ".!?" &&
                        (index == window.lastIndex || window[index + 1].isWhitespace()))
            }
            if (sentenceBoundary != null) return sentenceBoundary

            return window.indexOfLast { it.isWhitespace() }
                .takeIf { it >= FIRST_CHUNK_MIN_CHARS / 2 }
                ?: window.lastIndex
        }
    }
}

internal fun TtsPlaybackState.blocksVoiceCapture(): Boolean =
    this == TtsPlaybackState.SYNTHESIZING || this == TtsPlaybackState.PLAYING
