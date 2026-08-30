package com.clawsses.phone.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.clawsses.phone.glasses.RokidDeviceFacade
import com.clawsses.phone.glasses.ProductionRokidDeviceFacade
import com.clawsses.phone.util.SecurePreferences
import com.clawsses.shared.TtsVoiceCommands
import com.clawsses.shared.VisionCommands
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong

internal class RecognitionAttemptGate {
    private val generation = AtomicLong(0L)

    fun begin(): Long = generation.incrementAndGet()
    fun cancel() { generation.incrementAndGet() }
    fun isCurrent(attemptId: Long): Boolean = generation.get() == attemptId
    fun complete(attemptId: Long): Boolean = generation.compareAndSet(attemptId, attemptId + 1L)
}

/**
 * Manages voice recognition with OpenAI Realtime as primary and Android SpeechRecognizer as fallback.
 *
 * Provides a unified interface for voice recognition with automatic fallback when OpenAI is
 * unavailable (no API key, network error, etc.).
 */
class VoiceRecognitionManager(
    private val context: Context,
    private val rokidDevice: RokidDeviceFacade = ProductionRokidDeviceFacade,
) {

    companion object {
        private const val TAG = "VoiceRecognitionMgr"
        private const val PREFS_NAME = "clawsses"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_VOICE_ENABLED = "openai_voice_enabled"
        private const val KEY_LONG_DICTATION_ENABLED = "long_dictation_enabled"
    }

    /**
     * Which voice recognition mode is currently active.
     */
    enum class RecognitionMode {
        NONE,           // Not currently listening
        OPENAI,         // Using OpenAI Realtime API
        LONG_DICTATION, // Recording a bounded Phone-microphone dictation
        TRANSCRIBING,   // Uploading the completed dictation
        FALLBACK        // Using Android's SpeechRecognizer
    }

    /**
     * Reason why we're using fallback instead of OpenAI.
     */
    enum class FallbackReason {
        NONE,                   // Not using fallback (using OpenAI)
        NO_API_KEY,             // No OpenAI API key configured
        DISABLED,               // OpenAI voice explicitly disabled in settings
        CONNECTION_FAILED,      // Failed to connect to OpenAI
        API_ERROR,              // OpenAI API returned an error
        PREFERENCE              // User prefers fallback
    }

    private val prefs = SecurePreferences.create(context, PREFS_NAME)

    private val openAIClient = OpenAIRealtimeClient()
    private val batchClient = OpenAiBatchTranscriptionClient(
        File(context.cacheDir, "voice-dictation"),
    )
    private val fallbackHandler = VoiceCommandHandler(context)
    private val attemptGate = RecognitionAttemptGate()
    private val directAudioCircuitBreaker = DirectAudioCircuitBreaker()
    @Volatile private var directGlassesAudioActive = false
    @Volatile private var activeDirectAttempt: DirectAttempt? = null
    @Volatile private var fallbackAttemptId: Long? = null

    private data class DirectAttempt(
        val id: Long,
        val languageTag: String?,
        val onResult: (VoiceCommandHandler.VoiceResult) -> Unit,
    )

    private val _activeMode = MutableStateFlow(RecognitionMode.NONE)
    val activeMode: StateFlow<RecognitionMode> = _activeMode.asStateFlow()

    private val _fallbackReason = MutableStateFlow(FallbackReason.NONE)
    val fallbackReason: StateFlow<FallbackReason> = _fallbackReason.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    var onPartialResult: ((String) -> Unit)? = null
    var onSpeechStopped: (() -> Unit)? = null

    init {
        fallbackHandler.initialize()
    }

    /**
     * Check if OpenAI voice recognition is available and configured.
     */
    fun isOpenAIAvailable(): Boolean {
        val apiKey = getOpenAIApiKey()
        val enabled = isOpenAIVoiceEnabled()
        return apiKey.isNotEmpty() && enabled
    }

    /**
     * Get the stored OpenAI API key.
     */
    fun getOpenAIApiKey(): String {
        return prefs.getString(KEY_OPENAI_API_KEY, "") ?: ""
    }

    /**
     * Store the OpenAI API key securely.
     */
    fun setOpenAIApiKey(apiKey: String) {
        prefs.edit().putString(KEY_OPENAI_API_KEY, apiKey).apply()
        Log.i(TAG, "OpenAI API key ${if (apiKey.isNotEmpty()) "saved" else "cleared"}")
    }

    /**
     * Check if OpenAI voice recognition is enabled in settings.
     */
    fun isOpenAIVoiceEnabled(): Boolean {
        return prefs.getBoolean(KEY_OPENAI_VOICE_ENABLED, true)
    }

    /**
     * Enable or disable OpenAI voice recognition.
     */
    fun setOpenAIVoiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OPENAI_VOICE_ENABLED, enabled).apply()
        Log.i(TAG, "OpenAI voice recognition ${if (enabled) "enabled" else "disabled"}")
    }

    fun isLongDictationEnabled(): Boolean = prefs.getBoolean(KEY_LONG_DICTATION_ENABLED, false)

    fun setLongDictationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LONG_DICTATION_ENABLED, enabled).apply()
        Log.i(TAG, "Manual long dictation ${if (enabled) "enabled" else "disabled"}")
    }

    fun manualInputMode(): VoiceInputMode = manualVoiceInputMode(
        longDictationEnabled = isLongDictationEnabled(),
        openAiAvailable = isOpenAIAvailable(),
    )

    /**
     * Start voice recognition. Will use OpenAI if available, otherwise falls back to Android.
     *
     * @param languageTag BCP-47 language tag (e.g., "en-US", "nl-NL")
     * @param onResult Callback for the final result
     */
    fun startListening(
        languageTag: String? = null,
        inputMode: VoiceInputMode = VoiceInputMode.REALTIME,
        onResult: (VoiceCommandHandler.VoiceResult) -> Unit
    ) {
        if (_isListening.value) {
            Log.w(TAG, "Already listening, stopping first")
            cancelListening()
        }

        val attemptId = attemptGate.begin()
        activeDirectAttempt = null
        fallbackAttemptId = null

        _isListening.value = true
        _lastError.value = null

        val apiKey = getOpenAIApiKey()
        val openAIEnabled = isOpenAIVoiceEnabled()

        if (apiKey.isEmpty()) {
            Log.i(TAG, "No OpenAI API key, using fallback")
            _fallbackReason.value = FallbackReason.NO_API_KEY
            startFallbackRecognition(languageTag, onResult, attemptId)
            return
        }

        if (!openAIEnabled) {
            Log.i(TAG, "OpenAI voice disabled, using fallback")
            _fallbackReason.value = FallbackReason.DISABLED
            startFallbackRecognition(languageTag, onResult, attemptId)
            return
        }

        if (inputMode == VoiceInputMode.LONG_DICTATION) {
            startLongDictation(apiKey, languageTag, onResult, attemptId)
            return
        }

        // Try OpenAI first
        Log.i(TAG, "Starting OpenAI voice recognition")
        _activeMode.value = RecognitionMode.OPENAI
        _fallbackReason.value = FallbackReason.NONE

        val useDirectGlassesAudio = rokidDevice.isConnected() &&
            directAudioCircuitBreaker.canStart(SystemClock.elapsedRealtime())
        if (useDirectGlassesAudio) {
            rokidDevice.onAudioStreamStarted = { codec, originCodec, channels, _ ->
                Log.i(
                    TAG,
                    "Direct glasses audio format: codec=$codec, originCodec=$originCodec, channels=$channels",
                )
            }
            rokidDevice.onAudioStreamData = { data, offset, length ->
                openAIClient.appendExternalAudio(data, offset, length)
            }
            rokidDevice.onAudioStreamFinished = {
                Log.d(TAG, "Direct glasses audio source finished")
            }
            directGlassesAudioActive = rokidDevice.startMicrophoneStream()
            if (directGlassesAudioActive) {
                activeDirectAttempt = DirectAttempt(attemptId, languageTag, onResult)
            }
            if (!directGlassesAudioActive) {
                rokidDevice.clearMicrophoneStreamCallbacks()
                Log.w(TAG, "Direct glasses audio request failed; using Android capture")
            }
        }

        openAIClient.startListening(
            apiKey = apiKey,
            languageTag = languageTag,
            useExternalAudio = directGlassesAudioActive,
            onPartial = { partialText ->
                if (attemptGate.isCurrent(attemptId)) onPartialResult?.invoke(partialText)
            },
            onSpeechStopped = {
                if (attemptGate.isCurrent(attemptId)) {
                    stopDirectGlassesAudio()
                    onSpeechStopped?.invoke()
                }
            },
            onFinal = { finalText ->
                if (!attemptGate.isCurrent(attemptId)) return@startListening
                val usedDirectGlassesAudio = activeDirectAttempt?.id == attemptId
                stopDirectGlassesAudio()
                Log.i(TAG, "OpenAI transcription completed (${finalText.length} chars)")
                if (directCaptureNeedsPhoneFallback(usedDirectGlassesAudio, finalText)) {
                    Log.w(TAG, "Direct glasses capture was empty; retrying once with phone microphone")
                    activeDirectAttempt = null
                    rokidDevice.clearCommunicationDevice()
                    startFallbackRecognition(languageTag, onResult, attemptId)
                    return@startListening
                }
                if (!attemptGate.complete(attemptId)) return@startListening
                activeDirectAttempt = null
                _isListening.value = false
                _activeMode.value = RecognitionMode.NONE

                val result = if (finalText.isEmpty()) {
                    VoiceCommandHandler.VoiceResult.Text("")
                } else {
                    // Apply the same word mappings as fallback
                    processText(finalText)
                }
                onResult(result)
            },
            onError = { errorMessage ->
                if (!attemptGate.isCurrent(attemptId)) return@startListening
                val directCaptureFailed = activeDirectAttempt?.id == attemptId
                stopDirectGlassesAudio()
                Log.w(TAG, "OpenAI error: $errorMessage, falling back to Android")
                _lastError.value = errorMessage
                _fallbackReason.value = FallbackReason.API_ERROR

                if (directCaptureFailed) {
                    activeDirectAttempt = null
                    rokidDevice.clearCommunicationDevice()
                    startFallbackRecognition(languageTag, onResult, attemptId)
                    return@startListening
                }

                // Fall back to Android speech recognition
                startFallbackRecognition(languageTag, onResult, attemptId)
            }
        )
    }

    private fun startLongDictation(
        apiKey: String,
        languageTag: String?,
        onResult: (VoiceCommandHandler.VoiceResult) -> Unit,
        attemptId: Long,
    ) {
        Log.i(TAG, "Starting bounded long dictation on Phone microphone")
        stopDirectGlassesAudio()
        _activeMode.value = RecognitionMode.LONG_DICTATION
        _fallbackReason.value = FallbackReason.NONE
        batchClient.startListening(
            apiKey = apiKey,
            languageTag = languageTag,
            onSpeechStopped = {
                if (attemptGate.isCurrent(attemptId)) {
                    _activeMode.value = RecognitionMode.TRANSCRIBING
                    onSpeechStopped?.invoke()
                }
            },
            onFinal = { finalText ->
                if (!attemptGate.complete(attemptId)) return@startListening
                _isListening.value = false
                _activeMode.value = RecognitionMode.NONE
                onResult(
                    if (finalText.isBlank()) {
                        VoiceCommandHandler.VoiceResult.Text("")
                    } else {
                        processText(finalText)
                    },
                )
            },
            onError = { message ->
                if (!attemptGate.complete(attemptId)) return@startListening
                _lastError.value = message
                _isListening.value = false
                _activeMode.value = RecognitionMode.NONE
                onResult(VoiceCommandHandler.VoiceResult.Error(message))
            },
        )
    }

    private fun startFallbackRecognition(
        languageTag: String?,
        onResult: (VoiceCommandHandler.VoiceResult) -> Unit,
        attemptId: Long,
    ) {
        if (!attemptGate.isCurrent(attemptId) || fallbackAttemptId == attemptId) return
        fallbackAttemptId = attemptId
        Log.i(TAG, "Starting fallback (Android) voice recognition")
        _activeMode.value = RecognitionMode.FALLBACK

        fallbackHandler.onPartialResult = { partialText ->
            if (attemptGate.isCurrent(attemptId)) onPartialResult?.invoke(partialText)
        }

        fallbackHandler.startListening(languageTag = languageTag) { result ->
            if (!attemptGate.complete(attemptId)) return@startListening
            fallbackAttemptId = null
            _isListening.value = false
            _activeMode.value = RecognitionMode.NONE
            onResult(result)
        }
    }

    /**
     * Process transcribed text with word mappings (same as VoiceCommandHandler).
     */
    private fun processText(text: String): VoiceCommandHandler.VoiceResult {
        val lowerText = text.lowercase().trim()

        TtsVoiceCommands.match(text)?.let { command ->
            return VoiceCommandHandler.VoiceResult.Command(command)
        }

        // Check for special commands
        val commands = setOf(
            "escape", "scroll up", "scroll down", "take screenshot",
            "take photo", "take and send photo", "foto aufnehmen",
            "foto aufnehmen und senden", "stop talk mode", "talk mode off",
            "talk modus stoppen", "talk modus aus", "switch mode",
            "navigate mode", "scroll mode", "command mode"
        ) + VisionCommands.phrases
        for (command in commands) {
            if (lowerText == command || lowerText.startsWith("$command ")) {
                return VoiceCommandHandler.VoiceResult.Command(command)
            }
        }

        // Apply word mappings
        val mappings = mapOf(
            "slash" to "/", "forward slash" to "/", "backslash" to "\\",
            "dot" to ".", "period" to ".", "comma" to ",", "colon" to ":",
            "semicolon" to ";", "dash" to "-", "hyphen" to "-", "underscore" to "_",
            "at" to "@", "at sign" to "@", "hash" to "#", "hashtag" to "#",
            "pound" to "#", "dollar" to "$", "dollar sign" to "$", "percent" to "%",
            "caret" to "^", "ampersand" to "&", "and sign" to "&", "asterisk" to "*",
            "star" to "*", "open paren" to "(", "close paren" to ")",
            "open bracket" to "[", "close bracket" to "]", "open brace" to "{",
            "close brace" to "}", "pipe" to "|", "tilde" to "~", "backtick" to "`",
            "quote" to "\"", "single quote" to "'", "apostrophe" to "'",
            "equals" to "=", "plus" to "+", "minus" to "-", "less than" to "<",
            "greater than" to ">", "space" to " ", "newline" to "\n",
            "enter" to "\n", "tab" to "\t"
        )

        var processedText = text
        for ((word, symbol) in mappings) {
            processedText = processedText.replace(
                Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE),
                symbol
            )
        }

        return VoiceCommandHandler.VoiceResult.Text(processedText)
    }

    /**
     * Stop any active voice recognition.
     */
    fun stopListening() {
        if (_activeMode.value == RecognitionMode.LONG_DICTATION) {
            batchClient.stopListening()
        } else {
            cancelListening()
        }
    }

    /** Cancel recognition and invalidate every callback from the previous attempt. */
    fun cancelListening() {
        attemptGate.cancel()
        stopDirectGlassesAudio()
        activeDirectAttempt = null
        fallbackAttemptId = null
        when (_activeMode.value) {
            RecognitionMode.OPENAI -> {
                openAIClient.cancelListening()
            }
            RecognitionMode.LONG_DICTATION,
            RecognitionMode.TRANSCRIBING -> {
                batchClient.cancelListening()
            }
            RecognitionMode.FALLBACK -> {
                fallbackHandler.cancelListening()
            }
            RecognitionMode.NONE -> {
                // Nothing to stop
            }
        }
        _isListening.value = false
        _activeMode.value = RecognitionMode.NONE
    }

    /** Arm the direct-audio grace period after a connection that previously failed mid-capture. */
    fun onGlassesConnected() {
        directAudioCircuitBreaker.onConnected(SystemClock.elapsedRealtime())
    }

    /**
     * Move an in-flight direct CXR capture to the phone microphone before reconnect processing.
     * Returns true when the current recognition attempt continues on the phone.
     */
    fun onGlassesDisconnected(): Boolean {
        val directAttempt = activeDirectAttempt
        directAudioCircuitBreaker.onDisconnect(duringDirectAttempt = directAttempt != null)
        if (directAttempt == null || !attemptGate.isCurrent(directAttempt.id)) return false

        Log.w(TAG, "CXR disconnected during direct capture; switching to phone microphone")
        activeDirectAttempt = null
        stopDirectGlassesAudio()
        openAIClient.cancelListening()
        rokidDevice.clearCommunicationDevice()
        startFallbackRecognition(
            directAttempt.languageTag,
            directAttempt.onResult,
            directAttempt.id,
        )
        return fallbackAttemptId == directAttempt.id
    }

    /**
     * Get a human-readable description of the current recognition mode.
     */
    fun getModeDescription(): String {
        return when (_activeMode.value) {
            RecognitionMode.OPENAI -> "OpenAI"
            RecognitionMode.LONG_DICTATION -> "Long dictation"
            RecognitionMode.TRANSCRIBING -> "Transcribing"
            RecognitionMode.FALLBACK -> {
                when (_fallbackReason.value) {
                    FallbackReason.NO_API_KEY -> "Device (no API key)"
                    FallbackReason.DISABLED -> "Device (OpenAI disabled)"
                    FallbackReason.CONNECTION_FAILED -> "Device (connection failed)"
                    FallbackReason.API_ERROR -> "Device (API error)"
                    else -> "Device"
                }
            }
            RecognitionMode.NONE -> "Idle"
        }
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        cancelListening()
        openAIClient.destroy()
        batchClient.destroy()
        fallbackHandler.cleanup()
    }

    private fun stopDirectGlassesAudio() {
        if (directGlassesAudioActive) {
            directGlassesAudioActive = false
            rokidDevice.stopMicrophoneStream()
        }
        rokidDevice.clearMicrophoneStreamCallbacks()
    }
}
