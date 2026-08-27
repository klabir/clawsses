package com.clawsses.phone.runtime

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.clawsses.phone.glasses.GlassesConnectionManager
import com.clawsses.phone.glasses.RokidSdkManager
import com.clawsses.phone.voice.VoiceCommandHandler
import com.clawsses.phone.voice.VoiceLanguageManager
import com.clawsses.phone.voice.VoiceRecognitionManager
import com.clawsses.phone.voice.RecognitionAttemptGate
import com.clawsses.shared.TtsVoiceCommands
import org.json.JSONObject

/**
 * Owns non-Talk-Mode voice capture for the lifetime of the phone process.
 *
 * Both the OpenAI/direct-glasses path and the final Android phone-mic fallback converge on the
 * same result reducer. Generation IDs make callbacks from a cancelled or superseded capture inert.
 */
class StagedVoiceCoordinator(
    private val glassesManager: GlassesConnectionManager,
    private val voiceHandler: VoiceCommandHandler,
    private val voiceLanguageManager: VoiceLanguageManager,
    private val voiceRecognitionManager: VoiceRecognitionManager,
    private val stopCurrentTtsOutput: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attemptGate = RecognitionAttemptGate()

    fun start() {
        val attemptId = attemptGate.begin()
        val languageTag = voiceLanguageManager.getActiveLanguageTag()
        val mode = if (voiceRecognitionManager.isOpenAIAvailable()) "openai" else "device"

        voiceRecognitionManager.cancelListening()
        voiceHandler.cancelListening()
        RokidSdkManager.setCommunicationDevice()
        RokidSdkManager.sendAsrContent("...")
        sendVoiceState("listening", mode)

        voiceRecognitionManager.onSpeechStopped = {
            if (attemptGate.isCurrent(attemptId)) sendVoiceState("processing", mode)
        }
        voiceRecognitionManager.startListening(languageTag) { result ->
            if (!attemptGate.isCurrent(attemptId)) return@startListening
            when (result) {
                is VoiceCommandHandler.VoiceResult.Error -> startPhoneFallback(attemptId, languageTag)
                else -> complete(attemptId, result)
            }
        }
    }

    fun cancel(sendIdle: Boolean = true) {
        attemptGate.cancel()
        voiceRecognitionManager.cancelListening()
        voiceRecognitionManager.onSpeechStopped = null
        voiceHandler.cancelListening()
        RokidSdkManager.clearCommunicationDevice()
        if (sendIdle) sendVoiceState("idle")
    }

    private fun startPhoneFallback(attemptId: Long, languageTag: String?) {
        if (!attemptGate.isCurrent(attemptId)) return
        Log.w(TAG, "Primary recognition failed; retrying once with phone microphone")
        voiceRecognitionManager.cancelListening()
        RokidSdkManager.clearCommunicationDevice()
        mainHandler.postDelayed({
            if (!attemptGate.isCurrent(attemptId)) return@postDelayed
            voiceHandler.startListening(languageTag) { result -> complete(attemptId, result) }
        }, FALLBACK_DELAY_MS)
    }

    private fun complete(attemptId: Long, result: VoiceCommandHandler.VoiceResult) {
        if (!attemptGate.complete(attemptId)) return
        voiceRecognitionManager.onSpeechStopped = null
        RokidSdkManager.clearCommunicationDevice()
        when (result) {
            is VoiceCommandHandler.VoiceResult.Text -> completeText(result.text)
            is VoiceCommandHandler.VoiceResult.Command -> completeCommand(result.command)
            is VoiceCommandHandler.VoiceResult.Error -> completeError(result.message)
        }
    }

    private fun completeText(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) {
            RokidSdkManager.notifyAsrNone()
            sendVoiceState("idle")
            postExit(EMPTY_EXIT_DELAY_MS)
            return
        }
        RokidSdkManager.sendAsrContent(text)
        RokidSdkManager.notifyAsrEnd()
        sendVoiceResult("text", text)
        postExit(TEXT_EXIT_DELAY_MS)
    }

    private fun completeCommand(command: String) {
        if (TtsVoiceCommands.isStopCurrentOutput(command)) stopCurrentTtsOutput()
        RokidSdkManager.sendAsrContent(command)
        RokidSdkManager.notifyAsrEnd()
        sendVoiceResult("command", command)
        postExit(COMMAND_EXIT_DELAY_MS)
    }

    private fun completeError(message: String) {
        RokidSdkManager.notifyAsrError()
        sendVoiceResult("error", message)
        postExit(ERROR_EXIT_DELAY_MS)
    }

    private fun sendVoiceState(state: String, mode: String? = null) {
        glassesManager.sendRawMessage(
            JSONObject().apply {
                put("type", "voice_state")
                put("state", state)
                if (mode != null) put("mode", mode)
            }.toString(),
        )
    }

    private fun sendVoiceResult(type: String, text: String) {
        glassesManager.sendRawMessage(
            JSONObject().apply {
                put("type", "voice_result")
                put("result_type", type)
                put("text", text)
            }.toString(),
        )
    }

    private fun postExit(delayMs: Long) {
        mainHandler.postDelayed(RokidSdkManager::sendExitEvent, delayMs)
    }

    companion object {
        private const val TAG = "StagedVoice"
        private const val FALLBACK_DELAY_MS = 200L
        private const val EMPTY_EXIT_DELAY_MS = 500L
        private const val COMMAND_EXIT_DELAY_MS = 1_000L
        private const val TEXT_EXIT_DELAY_MS = 1_500L
        private const val ERROR_EXIT_DELAY_MS = 2_000L
    }
}
