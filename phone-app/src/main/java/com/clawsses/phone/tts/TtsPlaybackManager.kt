package com.clawsses.phone.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import com.clawsses.phone.audio.AudioSessionCoordinator
import com.clawsses.phone.audio.AudioSessionLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
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
    private val audioSessionCoordinator: AudioSessionCoordinator,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val generation = AtomicLong(0)
    private var synthesisJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private val audioTrackLock = Any()
    private var currentTempFile: File? = null
    private val queuedTempFiles = ArrayDeque<File>()
    private var completedSynthesisGeneration = NO_GENERATION
    private var playbackStartedGeneration = NO_GENERATION
    private var requestStartedAtMs = 0L
    private var lastText: String? = null
    private var audioSessionLease: AudioSessionLease? = null
    @Volatile private var requireBluetoothOutput = false

    private val _state = MutableStateFlow(TtsPlaybackState.IDLE)
    val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

    private val _canReplay = MutableStateFlow(false)
    val canReplay: StateFlow<Boolean> = _canReplay.asStateFlow()

    fun prepareOutput(requireBluetoothOutput: Boolean) {
        this.requireBluetoothOutput = requireBluetoothOutput
    }

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
        val lease = audioSessionCoordinator.beginPlayback {
            scope.launch { stop() }
        }
        if (lease == null) {
            Log.w(TAG, "TTS audio focus request denied")
            _state.value = TtsPlaybackState.ERROR
            return
        }
        audioSessionLease = lease
        playbackActive.set(true)
        lastText = normalized
        _canReplay.value = true
        _state.value = TtsPlaybackState.SYNTHESIZING
        val requestGeneration = generation.incrementAndGet()
        val speed = settings.speed.value.toDouble()
        completedSynthesisGeneration = NO_GENERATION
        playbackStartedGeneration = NO_GENERATION
        requestStartedAtMs = SystemClock.elapsedRealtime()

        synthesisJob = if (provider == TtsProvider.OPENAI) {
            scope.launch {
                streamOpenAiPcm(
                    requestGeneration = requestGeneration,
                    apiKey = key,
                    voice = voice,
                    text = normalized,
                    speed = speed,
                )
            }
        } else scope.launch {
            val pendingFiles = mutableListOf<File>()
            try {
                val chunks = splitForSynthesis(normalized)
                Log.i(
                    TAG,
                    "TTS synthesis started: chunks=${chunks.size}, firstChunkChars=${chunks.first().length}",
                )
                chunks.forEachIndexed { index, chunk ->
                    val chunkStartedAtMs = SystemClock.elapsedRealtime()
                    val result = elevenLabsClient.synthesize(key, voice, chunk, speed)
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

    private suspend fun streamOpenAiPcm(
        requestGeneration: Long,
        apiKey: String,
        voice: String,
        text: String,
        speed: Double,
    ) {
        val chunks = splitForSynthesis(text)
        val firstAudioReceived = CompletableDeferred<Unit>()
        val followUpAudio = Channel<Result<ByteArray>>(capacity = 1)
        val startupAudio = ByteArrayOutputStream(PCM_START_BUFFER_BYTES)
        var totalBytesWritten = 0L
        var playbackStage = "initialization"
        try {
            Log.i(
                TAG,
                "TTS PCM stream started: chunks=${chunks.size}, firstChunkChars=${chunks.first().length}",
            )
            coroutineScope {
                launch {
                    try {
                        firstAudioReceived.await()
                        chunks.drop(1).forEachIndexed { index, chunk ->
                            if (!isActive || generation.get() != requestGeneration) return@launch
                            val startedAtMs = SystemClock.elapsedRealtime()
                            val result = openAiClient.synthesizePcm(
                                apiKey = apiKey,
                                voice = voice,
                                text = chunk,
                                speed = speed,
                            )
                            Log.i(
                                TAG,
                                "TTS PCM follow-up synthesized: ${index + 2}/${chunks.size}, " +
                                    "chars=${chunk.length}, durationMs=" +
                                    (SystemClock.elapsedRealtime() - startedAtMs),
                            )
                            followUpAudio.send(result)
                        }
                    } finally {
                        followUpAudio.close()
                    }
                }

                val track = createPcmAudioTrack(requestGeneration)
                val frameWriter = PcmFrameAssembler { bytes, offset, count ->
                    writePcm(track, bytes, count, offset)
                }
                playbackStage = "first-stream"
                openAiClient.streamPcm(
                    apiKey = apiKey,
                    voice = voice,
                    text = chunks.first(),
                    speed = speed,
                ) { buffer, byteCount ->
                    playbackStage = "first-write"
                    check(generation.get() == requestGeneration) { "Stale TTS stream" }
                    if (!firstAudioReceived.isCompleted) {
                        val startupNeeded = PCM_START_BUFFER_BYTES - startupAudio.size()
                        val startupBytes = minOf(byteCount, startupNeeded)
                        startupAudio.write(buffer, 0, startupBytes)
                        if (startupAudio.size() == PCM_START_BUFFER_BYTES) {
                            val prebuffered = startupAudio.toByteArray()
                            totalBytesWritten += writePcm(track, prebuffered, prebuffered.size)
                            startPcmTrack(track, requestGeneration, firstAudioReceived)
                            val remaining = byteCount - startupBytes
                            if (remaining > 0) {
                                totalBytesWritten += frameWriter.write(
                                    buffer.copyOfRange(startupBytes, byteCount),
                                    remaining,
                                )
                            }
                        }
                    } else {
                        totalBytesWritten += frameWriter.write(buffer, byteCount)
                    }
                }.getOrThrow()
                playbackStage = "first-stream-complete"
                if (!firstAudioReceived.isCompleted && startupAudio.size() > 0) {
                    val prebuffered = startupAudio.toByteArray()
                    totalBytesWritten += writePcm(track, prebuffered, prebuffered.size)
                    startPcmTrack(track, requestGeneration, firstAudioReceived)
                }
                check(firstAudioReceived.isCompleted) { "Empty OpenAI PCM stream" }

                for (result in followUpAudio) {
                    playbackStage = "follow-up-write"
                    if (!isActive || generation.get() != requestGeneration) return@coroutineScope
                    val bytes = result.getOrThrow()
                    totalBytesWritten += frameWriter.write(bytes, bytes.size)
                }
                frameWriter.finish()
                playbackStage = "drain"
                awaitPcmDrain(track, totalBytesWritten, requestGeneration)
            }

            withContext(Dispatchers.Main) {
                if (generation.get() == requestGeneration) {
                    releaseAudioTrack()
                    finishPlayback()
                }
            }
        } catch (error: Exception) {
            if (generation.get() == requestGeneration) {
                Log.e(
                    TAG,
                    "TTS PCM playback failed: stage=$playbackStage, " +
                        "reason=${safePcmFailureReason(error)}",
                )
                withContext(Dispatchers.Main) {
                    failPlayback(requestGeneration)
                }
            }
        } finally {
            followUpAudio.cancel()
            releaseAudioTrack()
        }
    }

    private fun createPcmAudioTrack(requestGeneration: Long): AudioTrack {
        check(generation.get() == requestGeneration)
        val minBufferBytes = AudioTrack.getMinBufferSize(
            PCM_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) { "Unsupported PCM output format" }
        val playbackOutput = awaitPreferredPcmOutput()
        check(!requireBluetoothOutput || playbackOutput != null) {
            "Glasses Bluetooth media output unavailable"
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (requireBluetoothOutput) AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PCM_SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferBytes, PCM_BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) {
            track.release()
            "Unable to initialize PCM output"
        }
        playbackOutput?.let { output ->
            val accepted = track.setPreferredDevice(output)
            Log.i(
                TAG,
                "TTS PCM preferred Bluetooth output: type=${output.type}, accepted=$accepted",
            )
        } ?: Log.i(TAG, "TTS PCM using active Android media route")
        synchronized(audioTrackLock) {
            audioTrack = track
        }
        return track
    }

    private fun writePcm(
        track: AudioTrack,
        bytes: ByteArray,
        byteCount: Int,
        offset: Int = 0,
    ): Int {
        return writeFully(
            byteCount = byteCount,
            write = { relativeOffset, remaining ->
                track.write(
                    bytes,
                    offset + relativeOffset,
                    remaining,
                    AudioTrack.WRITE_BLOCKING,
                )
            },
            onNoProgress = { SystemClock.sleep(PCM_WRITE_RETRY_DELAY_MS) },
        )
    }

    private fun startPcmTrack(
        track: AudioTrack,
        requestGeneration: Long,
        firstAudioReceived: CompletableDeferred<Unit>,
    ) {
        track.play()
        firstAudioReceived.complete(Unit)
        playbackStartedGeneration = requestGeneration
        _state.value = TtsPlaybackState.PLAYING
        Log.i(
            TAG,
            "TTS first PCM audio started: latencyMs=" +
                (SystemClock.elapsedRealtime() - requestStartedAtMs),
        )
    }

    private fun safePcmFailureReason(error: Exception): String {
        val message = error.message.orEmpty()
        return if (message.startsWith("AudioTrack write failed") ||
            message in setOf(
                "Stale TTS stream",
                "Empty OpenAI PCM stream",
                "Unsupported PCM output format",
                "Unable to initialize PCM output",
                "Glasses Bluetooth media output unavailable",
                "Incomplete PCM frame at end of stream",
            )
        ) message else error.javaClass.simpleName
    }

    private suspend fun awaitPcmDrain(
        track: AudioTrack,
        totalBytesWritten: Long,
        requestGeneration: Long,
    ) {
        val totalFrames = totalBytesWritten / PCM_BYTES_PER_FRAME
        val deadlineMs = SystemClock.elapsedRealtime() +
            (totalFrames * 1_000L / PCM_SAMPLE_RATE_HZ) + PCM_DRAIN_GRACE_MS
        while (generation.get() == requestGeneration &&
            (track.playbackHeadPosition.toLong() and UINT_MASK) < totalFrames &&
            SystemClock.elapsedRealtime() < deadlineMs
        ) {
            delay(PCM_DRAIN_POLL_MS)
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
            val mediaOutput = preferredPcmOutput()
            check(!requireBluetoothOutput || mediaOutput != null) {
                "Glasses Bluetooth media output unavailable"
            }
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (requireBluetoothOutput) AudioAttributes.USAGE_VOICE_COMMUNICATION
                            else AudioAttributes.USAGE_MEDIA
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
                mediaOutput?.let { output ->
                    val accepted = setPreferredDevice(output)
                    Log.i(
                        TAG,
                        "TTS preferred Bluetooth media output: id=${output.id}, accepted=$accepted",
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
        releaseAudioSession()
        Log.i(TAG, "TTS playback completed")
    }

    private fun failPlayback(requestGeneration: Long) {
        if (generation.get() != requestGeneration) return
        generation.incrementAndGet()
        playbackActive.set(false)
        completedSynthesisGeneration = NO_GENERATION
        playbackStartedGeneration = NO_GENERATION
        releasePlayer()
        releaseAudioTrack()
        deleteAllTempFiles()
        releaseAudioSession()
        _state.value = TtsPlaybackState.ERROR
    }
    /** Keep glasses speech on Rokid's communication channel; Android resamples 24 kHz PCM for SCO. */
    private fun preferredPcmOutput(): AudioDeviceInfo? {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return null
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return selectPcmOutputType(outputs.map(AudioDeviceInfo::getType))?.let { selectedType ->
            val matching = outputs.filter { it.type == selectedType }
            matching.firstOrNull {
                it.productName?.toString()?.contains("Glasses", ignoreCase = true) == true
            } ?: matching.singleOrNull()
        }
    }

    private fun awaitPreferredPcmOutput(): AudioDeviceInfo? {
        val waitMs = if (requireBluetoothOutput) PCM_REQUIRED_ROUTE_WAIT_MS else PCM_ROUTE_WAIT_MS
        val deadlineMs = SystemClock.elapsedRealtime() + waitMs
        while (true) {
            preferredPcmOutput()?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadlineMs) return null
            SystemClock.sleep(PCM_ROUTE_POLL_MS)
        }
    }

    private fun stopInternal(updateState: Boolean) {
        playbackActive.set(false)
        generation.incrementAndGet()
        synthesisJob?.cancel()
        synthesisJob = null
        completedSynthesisGeneration = NO_GENERATION
        playbackStartedGeneration = NO_GENERATION
        releasePlayer()
        releaseAudioTrack()
        deleteAllTempFiles()
        releaseAudioSession()
        if (updateState) _state.value = TtsPlaybackState.IDLE
    }

    private fun releaseAudioSession() {
        audioSessionLease?.let(audioSessionCoordinator::release)
        audioSessionLease = null
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

    private fun releaseAudioTrack() {
        val track = synchronized(audioTrackLock) {
            audioTrack.also { audioTrack = null }
        } ?: return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.release()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to release TTS AudioTrack: ${error.javaClass.simpleName}")
        }
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
        private const val PCM_SAMPLE_RATE_HZ = 24_000
        private const val PCM_BYTES_PER_FRAME = 2L
        private const val PCM_BUFFER_BYTES = 24_000
        private const val PCM_START_BUFFER_BYTES = 24_000
        private const val PCM_DRAIN_POLL_MS = 20L
        private const val PCM_DRAIN_GRACE_MS = 2_000L
        private const val PCM_WRITE_RETRY_DELAY_MS = 10L
        private const val PCM_ROUTE_POLL_MS = 25L
        private const val PCM_ROUTE_WAIT_MS = 500L
        private const val PCM_REQUIRED_ROUTE_WAIT_MS = 3_000L
        private const val PCM_MAX_NO_PROGRESS_WRITES = 50
        private const val UINT_MASK = 0xffffffffL
        private const val NO_GENERATION = -1L
        private val playbackActive = AtomicBoolean(false)

        fun isPlaybackActive(): Boolean = playbackActive.get()

        internal fun selectPcmOutputType(availableTypes: List<Int>): Int? = when {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO in availableTypes ->
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            else -> null
        }

        internal fun writeFully(
            byteCount: Int,
            write: (offset: Int, remaining: Int) -> Int,
            onNoProgress: () -> Unit,
        ): Int {
            var offset = 0
            var consecutiveNoProgressWrites = 0
            while (offset < byteCount) {
                val remaining = byteCount - offset
                val written = write(offset, remaining)
                when {
                    written > 0 -> {
                        check(written <= remaining) { "AudioTrack wrote beyond requested buffer" }
                        offset += written
                        consecutiveNoProgressWrites = 0
                    }
                    written == 0 && consecutiveNoProgressWrites < PCM_MAX_NO_PROGRESS_WRITES -> {
                        consecutiveNoProgressWrites += 1
                        onNoProgress()
                    }
                    else -> error("AudioTrack write failed ($written)")
                }
            }
            return offset
        }

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

internal class PcmFrameAssembler(
    private val sink: (bytes: ByteArray, offset: Int, byteCount: Int) -> Int,
) {
    private var pendingByte: Byte? = null

    fun write(bytes: ByteArray, byteCount: Int): Int {
        require(byteCount in 0..bytes.size)
        var sourceOffset = 0
        var written = 0

        pendingByte?.let { previous ->
            if (byteCount == 0) return 0
            written += sink(byteArrayOf(previous, bytes[0]), 0, PCM_FRAME_BYTES)
            pendingByte = null
            sourceOffset = 1
        }

        val alignedByteCount = (byteCount - sourceOffset) / PCM_FRAME_BYTES * PCM_FRAME_BYTES
        if (alignedByteCount > 0) {
            written += sink(bytes, sourceOffset, alignedByteCount)
            sourceOffset += alignedByteCount
        }
        if (sourceOffset < byteCount) pendingByte = bytes[sourceOffset]
        return written
    }

    fun finish() {
        check(pendingByte == null) { "Incomplete PCM frame at end of stream" }
    }

    companion object {
        private const val PCM_FRAME_BYTES = 2
    }
}

internal fun TtsPlaybackState.blocksVoiceCapture(): Boolean =
    this == TtsPlaybackState.SYNTHESIZING || this == TtsPlaybackState.PLAYING
