package com.clawsses.phone.tts

import android.content.Context
import android.media.MediaPlayer
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
        lastText = normalized
        _canReplay.value = true
        _state.value = TtsPlaybackState.SYNTHESIZING
        val requestGeneration = generation.incrementAndGet()
        val speed = settings.speed.value.toDouble()

        synthesisJob = scope.launch {
            try {
                val audioParts = splitForSynthesis(normalized).map { chunk ->
                    val result = when (provider) {
                        TtsProvider.ELEVENLABS ->
                            elevenLabsClient.synthesize(key, voice, chunk, speed)
                        TtsProvider.OPENAI ->
                            openAiClient.synthesize(key, voice, chunk, speed)
                    }
                    result.getOrThrow()
                }
                if (!isActive || generation.get() != requestGeneration) return@launch

                val tempFile = File.createTempFile("tts_", ".mp3", context.cacheDir)
                FileOutputStream(tempFile).use { output ->
                    audioParts.forEach(output::write)
                }
                if (!isActive || generation.get() != requestGeneration) {
                    tempFile.delete()
                    return@launch
                }
                currentTempFile = tempFile
                withContext(Dispatchers.Main) {
                    if (generation.get() == requestGeneration) playAudioFile(tempFile)
                }
            } catch (error: Exception) {
                if (generation.get() == requestGeneration) {
                    Log.e(TAG, "TTS synthesis failed: ${error.javaClass.simpleName}")
                    _state.value = TtsPlaybackState.ERROR
                    deleteTempFile()
                }
            }
        }
    }

    fun replay() {
        lastText?.let(::speak)
    }

    fun stop() {
        stopInternal(updateState = true)
    }

    private fun playAudioFile(file: File) {
        try {
            releasePlayer()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    releasePlayer()
                    deleteTempFile()
                    _state.value = TtsPlaybackState.IDLE
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    releasePlayer()
                    deleteTempFile()
                    _state.value = TtsPlaybackState.ERROR
                    true
                }
                prepare()
                start()
            }
            _state.value = TtsPlaybackState.PLAYING
        } catch (error: Exception) {
            Log.e(TAG, "TTS playback failed: ${error.javaClass.simpleName}")
            releasePlayer()
            deleteTempFile()
            _state.value = TtsPlaybackState.ERROR
        }
    }

    private fun stopInternal(updateState: Boolean) {
        generation.incrementAndGet()
        synthesisJob?.cancel()
        synthesisJob = null
        releasePlayer()
        deleteTempFile()
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

    private fun deleteTempFile() {
        currentTempFile?.let { file ->
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Unable to delete private TTS cache file")
            }
        }
        currentTempFile = null
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
        private const val MAX_CHUNK_CHARS = 3_500

        internal fun splitForSynthesis(text: String): List<String> {
            if (text.length <= MAX_CHUNK_CHARS) return listOf(text)
            val chunks = mutableListOf<String>()
            var remaining = text.trim()
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
    }
}
