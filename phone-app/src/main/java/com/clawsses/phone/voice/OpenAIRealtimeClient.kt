package com.clawsses.phone.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenAI Realtime API client for streaming speech-to-text transcription.
 *
 * Designed to behave like Android's SpeechRecognizer:
 * - Auto-finishes when the user stops speaking (via server-side VAD)
 * - Delivers final result on the main thread
 * - Handles no-speech and transcription timeouts
 * - Safe for repeated use (idempotent cleanup, AtomicBoolean guard)
 *
 * Audio pre-buffering: AudioRecord starts immediately when startListening() is called,
 * before the WebSocket connection is established. The latest audio is kept in a bounded rolling
 * buffer and flushed to the server once the session is ready. This eliminates the normal
 * ~500-800ms gap where the user's first words would otherwise be lost without allowing a stalled
 * connection to retain PCM indefinitely.
 *
 * The mic stays active until local detection finds a sustained pause, then the client
 * explicitly commits that utterance and waits for the final transcript.
 *
 * Audio: 24kHz, 16-bit PCM, mono (required by OpenAI Realtime API).
 * Transcription: gpt-live-transcribe via a GA Realtime transcription session.
 * Local amplitude detection commits the audio buffer after the user stops speaking.
 */
class OpenAIRealtimeClient {

    companion object {
        private const val TAG = "OpenAIRealtime"
        private const val REALTIME_URL = "wss://api.openai.com/v1/realtime"
        private const val TRANSCRIPTION_MODEL = "gpt-live-transcribe"

        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 2

        // Send audio in small frames for responsive VAD (~20ms = 960 bytes at 24kHz 16-bit mono)
        private const val SEND_FRAME_BYTES = 960
        private const val MAX_PREBUFFER_FRAMES = 100 // Latest ~2 seconds at 20 ms/frame.
        private const val SPEECH_PEAK_THRESHOLD = 1_500
        private const val SPEECH_START_FRAME_COUNT = 3
        private const val SILENCE_FRAME_COUNT = 38

        private const val NO_SPEECH_TIMEOUT_MS = 10_000L
        private const val TRANSCRIPTION_TIMEOUT_MS = 5_000L
        private const val DONE_TIMEOUT_MS = 1_000L
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Guard: only the first call to deliverFinalResult/deliverError takes effect
    private val resultDelivered = AtomicBoolean(false)
    private val sessionIds = AtomicLong(0L)
    @Volatile private var activeSessionId = 0L

    @Volatile private var speechDetected = false
    @Volatile private var currentlySpeaking = false
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilentFrames = 0
    @Volatile private var commitPending = false
    private val audioCommitted = AtomicBoolean(false)

    // Audio pre-buffering: record starts before WebSocket is ready. Keep it bounded so a
    // stalled connection cannot retain PCM indefinitely.
    private val preBuffer = BoundedAudioFrameBuffer(MAX_PREBUFFER_FRAMES)
    private val preBufferDroppedFrames = AtomicLong(0L)
    @Volatile private var sessionReady = false

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var scope: CoroutineScope? = null
    @Volatile private var externalAudioInput = false
    private val externalAudioLock = Any()
    private var pendingExternalPcmByte: Byte? = null
    private val externalAudioTelemetry = PcmAudioTelemetry()

    @Volatile private var onPartialResult: ((String) -> Unit)? = null
    @Volatile private var onFinalResult: ((String) -> Unit)? = null
    @Volatile private var onError: ((String) -> Unit)? = null
    @Volatile private var onSpeechStopped: (() -> Unit)? = null

    private val transcriptLock = Any()
    private val accumulatedTranscript = StringBuilder()

    private var noSpeechTimeoutJob: Job? = null
    private var transcriptionTimeoutJob: Job? = null
    private var doneTimeoutJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Start a voice recognition session.
     *
     * Audio capture starts immediately (before WebSocket connects) and buffers PCM data.
     * Once the session is configured, buffered audio is flushed and streaming continues directly.
     *
     * @param apiKey OpenAI API key
     * @param languageTag BCP-47 language tag (e.g., "en-US", "nl-NL")
     * @param onPartial Callback for partial transcription (main thread)
     * @param onFinal Callback for final transcription (main thread)
     * @param onError Callback for errors (main thread)
     * @param onSpeechStopped Callback when VAD detects speech end (main thread), for "processing" UI
     */
    fun startListening(
        apiKey: String,
        languageTag: String? = null,
        useExternalAudio: Boolean = false,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onSpeechStopped: (() -> Unit)? = null
    ) {
        if (_isListening.value) {
            Log.w(TAG, "Already listening, cleaning up first")
            cleanupSilently()
        }

        val sessionId = sessionIds.incrementAndGet()
        activeSessionId = sessionId

        // Reset all state
        resultDelivered.set(false)
        speechDetected = false
        currentlySpeaking = false
        consecutiveSpeechFrames = 0
        consecutiveSilentFrames = 0
        commitPending = false
        audioCommitted.set(false)
        sessionReady = false
        externalAudioInput = useExternalAudio
        externalAudioTelemetry.reset()
        synchronized(externalAudioLock) { pendingExternalPcmByte = null }
        preBuffer.clear()
        preBufferDroppedFrames.set(0L)
        synchronized(transcriptLock) { accumulatedTranscript.clear() }

        this.onPartialResult = onPartial
        this.onFinalResult = onFinal
        this.onError = onError
        this.onSpeechStopped = onSpeechStopped

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        _connectionState.value = ConnectionState.Connecting
        _isListening.value = true

        // Start local capture immediately unless CXR will provide PCM from the glasses.
        // No-speech timeout starts later when the session is configured.
        if (!useExternalAudio) startAudioCapture()

        val url = "$REALTIME_URL?intent=transcription"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (activeSessionId != sessionId) {
                    webSocket.close(1000, "stale session")
                    return
                }
                Log.i(TAG, "WebSocket connected")
                _connectionState.value = ConnectionState.Connected
                configureSession(webSocket, languageTag)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (activeSessionId == sessionId) handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorMsg = t.message ?: "Connection failed"
                Log.e(TAG, "Realtime transcription WebSocket failed: ${t.javaClass.simpleName}")
                deliverError(errorMsg, sessionId)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing (code=$code)")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed (code=$code)")
                // If result wasn't delivered yet (unexpected close), deliver empty
                deliverFinalResult(sessionId)
            }
        })
    }

    private fun configureSession(webSocket: WebSocket, languageTag: String?) {
        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("type", "transcription")
                put("audio", JSONObject().apply {
                    put("input", JSONObject().apply {
                        put("format", JSONObject().apply {
                            put("type", "audio/pcm")
                            put("rate", SAMPLE_RATE)
                        })
                        put("transcription", JSONObject().apply {
                            put("model", TRANSCRIPTION_MODEL)
                            languageTag
                                ?.substringBefore('-')
                                ?.lowercase()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { put("languages", JSONArray().put(it)) }
                        })
                        put("turn_detection", JSONObject.NULL)
                    })
                })
            })
        }

        Log.d(TAG, "Sending session config")
        webSocket.send(sessionConfig.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "session.created" -> {
                    Log.i(TAG, "Session created, waiting for config confirmation")
                    // Don't start audio yet — wait for session.updated
                }

                "session.updated" -> {
                    Log.i(TAG, "Session configured, flushing pre-buffered audio")
                    sessionReady = true
                    // Recording coroutine will drain preBuffer on next iteration
                    if (!speechDetected) startNoSpeechTimeout()
                }

                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "Speech started")
                    speechDetected = true
                    currentlySpeaking = true
                    cancelNoSpeechTimeout()
                    cancelTranscriptionTimeout()  // Previous segment's timeout is irrelevant
                    cancelDoneTimeout()  // User is speaking again after a pause
                }

                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "Speech stopped — waiting for transcription (mic stays active)")
                    currentlySpeaking = false
                    // Retained for compatibility with models that provide server VAD events.
                    // DON'T stop recording — user might continue speaking after a pause
                    // Notify caller so they can show "processing" state on glasses
                    val speechStoppedCallback = onSpeechStopped
                    mainHandler.post { speechStoppedCallback?.invoke() }
                    startTranscriptionTimeout()
                }

                "input_audio_buffer.committed" -> {
                    Log.d(TAG, "Audio buffer committed")
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.optString("transcript", "")
                    Log.i(TAG, "Transcription completed (${transcript.length} chars)")
                    cancelTranscriptionTimeout()

                    if (transcript.isNotEmpty()) {
                        synchronized(transcriptLock) {
                            if (accumulatedTranscript.isNotEmpty()) {
                                accumulatedTranscript.append(" ")
                            }
                            accumulatedTranscript.append(transcript.trim())
                        }
                        // Send partial so glasses see the text before final delivery
                        val currentText = synchronized(transcriptLock) {
                            accumulatedTranscript.toString().trim()
                        }
                        val partialCallback = onPartialResult
                        mainHandler.post { partialCallback?.invoke(currentText) }
                    }

                    // Only start done timeout if user is NOT currently speaking.
                    // This transcription may be for a previous segment while the user
                    // has already started a new one.
                    if (!currentlySpeaking) {
                        startDoneTimeout()
                    } else {
                        Log.d(TAG, "User still speaking — not starting done timeout")
                    }
                }

                "conversation.item.input_audio_transcription.failed" -> {
                    val errorObj = json.optJSONObject("error")
                    val message = errorObj?.optString("message") ?: "Transcription failed"
                    Log.e(TAG, "Transcription failed")
                    cancelTranscriptionTimeout()
                    // Wait for more speech or done timeout
                    startDoneTimeout()
                }

                "error" -> {
                    val error = json.optJSONObject("error")
                    val message = error?.optString("message") ?: "Unknown error"
                    val code = error?.optString("code") ?: ""
                    Log.e(TAG, "Realtime transcription API error (code=$code)")
                    deliverError(message)
                }

                // Ignore response/conversation events — we only care about transcription
                else -> {
                    Log.v(TAG, "Event: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Realtime API message")
        }
    }

    private fun startAudioCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        ) * BUFFER_SIZE_MULTIPLIER

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            deliverError("Failed to calculate audio buffer size")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                deliverError("Failed to initialize AudioRecord")
                return
            }

            audioRecord?.startRecording()
            Log.i(TAG, "Audio recording started (24kHz, 16-bit PCM) — pre-buffering until session ready")

            recordingJob = scope?.launch {
                // Read in small frames (~20ms) for responsive VAD detection.
                // AudioRecord's internal buffer is larger to prevent overflow,
                // but we send small chunks so the server gets a smooth stream.
                val readBuffer = ByteArray(SEND_FRAME_BYTES)
                while (isActive) {
                    val bytesRead = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
                    if (bytesRead > 0) {
                        val chunk = readBuffer.copyOf(bytesRead)
                        updateLocalSpeechState(chunk)
                        if (!sessionReady) {
                            // Buffer audio until WebSocket session is configured
                            if (preBuffer.offer(chunk) &&
                                preBufferDroppedFrames.incrementAndGet() == 1L
                            ) {
                                Log.w(
                                    TAG,
                                    "Realtime session is slow; retaining only the latest audio window",
                                )
                            }
                        } else {
                            // Drain any pre-buffered audio first
                            var buffered = preBuffer.poll()
                            while (buffered != null) {
                                sendAudioData(buffered)
                                buffered = preBuffer.poll()
                            }
                            // Then send current chunk
                            sendAudioData(chunk)

                            if (commitPending && audioCommitted.compareAndSet(false, true)) {
                                commitAudioBuffer()
                                break
                            }
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            deliverError("Microphone permission denied")
        } catch (e: Exception) {
            deliverError("Audio capture error: ${e.message}")
        }
    }

    /** Feed 24 kHz mono PCM16 supplied by an external capture source such as CXR. */
    fun appendExternalAudio(data: ByteArray, offset: Int, length: Int) {
        if (!externalAudioInput || !_isListening.value || length <= 0 || offset < 0 ||
            offset + length > data.size
        ) return

        val chunk = synchronized(externalAudioLock) {
            val pending = pendingExternalPcmByte
            val total = length + if (pending != null) 1 else 0
            val evenLength = total and -2
            if (evenLength == 0) {
                pendingExternalPcmByte = data[offset]
                return@synchronized null
            }
            val assembled = ByteArray(evenLength)
            var sourceOffset = offset
            var targetOffset = 0
            if (pending != null) {
                assembled[0] = pending
                targetOffset++
            }
            val copyLength = evenLength - targetOffset
            if (copyLength > 0) {
                System.arraycopy(data, sourceOffset, assembled, targetOffset, copyLength)
            }
            val consumedFromInput = copyLength
            pendingExternalPcmByte = if (consumedFromInput < length) {
                data[offset + length - 1]
            } else null
            assembled
        } ?: return
        processAudioChunk(chunk)
    }

    private fun processAudioChunk(chunk: ByteArray) {
        val peak = if (externalAudioInput) externalAudioTelemetry.recordPcm16(chunk) else null
        updateLocalSpeechState(chunk, peak)
        if (!sessionReady) {
            if (preBuffer.offer(chunk) && preBufferDroppedFrames.incrementAndGet() == 1L) {
                Log.w(TAG, "Realtime session is slow; retaining only the latest audio window")
            }
            return
        }

        var buffered = preBuffer.poll()
        while (buffered != null) {
            sendAudioData(buffered)
            buffered = preBuffer.poll()
        }
        sendAudioData(chunk)
        if (commitPending && audioCommitted.compareAndSet(false, true)) {
            commitAudioBuffer()
        }
    }

    private fun sendAudioData(audioData: ByteArray) {
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
        val message = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64Audio)
        }
        try {
            webSocket?.send(message.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send audio frame")
        }
    }

    private fun updateLocalSpeechState(audioData: ByteArray, measuredPeak: Int? = null) {
        val peak = measuredPeak ?: pcm16Peak(audioData)

        if (!speechDetected) {
            consecutiveSpeechFrames = if (peak >= SPEECH_PEAK_THRESHOLD) {
                consecutiveSpeechFrames + 1
            } else {
                0
            }

            if (consecutiveSpeechFrames >= SPEECH_START_FRAME_COUNT) {
                speechDetected = true
                currentlySpeaking = true
                consecutiveSilentFrames = 0
                cancelNoSpeechTimeout()
                Log.d(TAG, "Speech started (local detection)")
            }
            return
        }

        if (peak >= SPEECH_PEAK_THRESHOLD) {
            currentlySpeaking = true
            consecutiveSilentFrames = 0
            return
        }

        consecutiveSilentFrames++
        if (consecutiveSilentFrames >= SILENCE_FRAME_COUNT && !commitPending) {
            currentlySpeaking = false
            commitPending = true
            Log.d(TAG, "Speech stopped (local detection) — committing audio")
        }
    }

    private fun commitAudioBuffer() {
        val message = JSONObject().apply {
            put("type", "input_audio_buffer.commit")
        }
        if (webSocket?.send(message.toString()) != true) {
            deliverError("Failed to commit audio buffer")
            return
        }

        val speechStoppedCallback = onSpeechStopped
        mainHandler.post { speechStoppedCallback?.invoke() }
        startTranscriptionTimeout()
    }

    private fun stopRecording() {
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.let { record ->
            try {
                record.stop()
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord")
            }
        }
        audioRecord = null
    }

    // --- Timeouts ---

    private fun startNoSpeechTimeout() {
        noSpeechTimeoutJob = scope?.launch {
            delay(NO_SPEECH_TIMEOUT_MS)
            Log.i(TAG, "No speech detected after ${NO_SPEECH_TIMEOUT_MS}ms — delivering empty result")
            deliverFinalResult()
        }
    }

    private fun cancelNoSpeechTimeout() {
        noSpeechTimeoutJob?.cancel()
        noSpeechTimeoutJob = null
    }

    private fun startTranscriptionTimeout() {
        transcriptionTimeoutJob?.cancel()
        transcriptionTimeoutJob = scope?.launch {
            delay(TRANSCRIPTION_TIMEOUT_MS)
            Log.w(TAG, "Transcription timeout after ${TRANSCRIPTION_TIMEOUT_MS}ms — delivering accumulated text")
            deliverFinalResult()
        }
    }

    private fun cancelTranscriptionTimeout() {
        transcriptionTimeoutJob?.cancel()
        transcriptionTimeoutJob = null
    }

    private fun startDoneTimeout() {
        doneTimeoutJob?.cancel()
        doneTimeoutJob = scope?.launch {
            delay(DONE_TIMEOUT_MS)
            Log.i(TAG, "No more speech after ${DONE_TIMEOUT_MS}ms — delivering final result")
            deliverFinalResult()
        }
    }

    private fun cancelDoneTimeout() {
        doneTimeoutJob?.cancel()
        doneTimeoutJob = null
    }

    // --- Result delivery ---

    /**
     * Deliver the final transcription result on the main thread.
     * Only the first call takes effect (guarded by AtomicBoolean).
     */
    private fun deliverFinalResult(expectedSessionId: Long = activeSessionId) {
        if (expectedSessionId == 0L || activeSessionId != expectedSessionId) return
        if (!resultDelivered.compareAndSet(false, true)) return

        val finalText = synchronized(transcriptLock) {
            accumulatedTranscript.toString().trim()
        }
        logExternalAudioTelemetry()
        Log.i(TAG, "Delivering final result (${finalText.length} chars)")

        val callback = onFinalResult
        mainHandler.post { callback?.invoke(finalText) }

        cleanupConnection(expectedSessionId)
    }

    /**
     * Deliver an error on the main thread.
     * Only the first call takes effect (guarded by AtomicBoolean).
     */
    private fun deliverError(message: String, expectedSessionId: Long = activeSessionId) {
        if (expectedSessionId == 0L || activeSessionId != expectedSessionId) return
        if (!resultDelivered.compareAndSet(false, true)) return

        logExternalAudioTelemetry()
        Log.e(TAG, "Delivering transcription error")
        _connectionState.value = ConnectionState.Error(message)

        val callback = onError
        mainHandler.post { callback?.invoke(message) }

        cleanupConnection(expectedSessionId)
    }

    // --- Lifecycle ---

    /**
     * Stop voice recognition. Delivers any accumulated text as the final result.
     */
    fun stopListening() {
        Log.i(TAG, "stopListening() called")
        val sessionId = activeSessionId
        stopRecording()
        deliverFinalResult(sessionId)
    }

    /** Cancel the active session without delivering text or an error callback. */
    fun cancelListening() {
        Log.i(TAG, "cancelListening() called")
        cleanupSilently()
    }

    /**
     * Clean up connection resources. Idempotent — safe to call multiple times.
     */
    private fun cleanupConnection(expectedSessionId: Long) {
        if (activeSessionId != expectedSessionId) return
        activeSessionId = 0L
        cancelNoSpeechTimeout()
        cancelTranscriptionTimeout()
        cancelDoneTimeout()
        stopRecording()

        sessionReady = false
        externalAudioInput = false
        synchronized(externalAudioLock) { pendingExternalPcmByte = null }
        preBuffer.clear()

        webSocket?.let {
            try { it.close(1000, "done") } catch (_: Exception) {}
        }
        webSocket = null

        scope?.cancel()
        scope = null

        _isListening.value = false
        _connectionState.value = ConnectionState.Disconnected

        onPartialResult = null
        onFinalResult = null
        onError = null
        onSpeechStopped = null
    }

    private fun logExternalAudioTelemetry() {
        if (!externalAudioInput) return
        val snapshot = externalAudioTelemetry.snapshot()
        Log.i(
            TAG,
            "External audio telemetry: bytes=${snapshot.totalBytes}, maxPeak=${snapshot.maxPeak}",
        )
    }

    /**
     * Silent cleanup without delivering results. Used when re-starting a new session.
     */
    private fun cleanupSilently() {
        activeSessionId = 0L
        cancelNoSpeechTimeout()
        cancelTranscriptionTimeout()
        cancelDoneTimeout()
        stopRecording()

        sessionReady = false
        externalAudioInput = false
        synchronized(externalAudioLock) { pendingExternalPcmByte = null }
        preBuffer.clear()

        webSocket?.let {
            try { it.close(1000, "restart") } catch (_: Exception) {}
        }
        webSocket = null

        scope?.cancel()
        scope = null

        _isListening.value = false
        _connectionState.value = ConnectionState.Disconnected

        onPartialResult = null
        onFinalResult = null
        onError = null
        onSpeechStopped = null
    }

    /**
     * Force cleanup of all resources.
     */
    fun destroy() {
        cleanupSilently()
        client.dispatcher.executorService.shutdown()
    }
}
