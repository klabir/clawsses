package com.clawsses.phone.voice

import android.content.Context
import com.clawsses.phone.audio.AudioSessionCoordinator
import com.clawsses.phone.audio.AudioSessionLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LiveCaptionState(
    val enabled: Boolean = false,
    val sourceText: String = "",
    val translatedText: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val error: String? = null,
)

class LiveCaptionManager(
    context: Context,
    private val audioSessionCoordinator: AudioSessionCoordinator,
) {
    private val recognition = VoiceRecognitionManager(context)
    private val translator = OpenAiTranslationClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(LiveCaptionState())
    val state: StateFlow<LiveCaptionState> = mutableState.asStateFlow()

    private var cycleJob: Job? = null
    private var generation = 0L
    private var sourceLanguage: String? = null
    private var targetLanguage: String = "English"
    private var translate: Boolean = false
    private var captureLease: AudioSessionLease? = null

    fun start(sourceLanguage: String?, targetLanguage: String, translate: Boolean) {
        stop()
        val lease = audioSessionCoordinator.beginCapture()
        if (lease == null) {
            mutableState.value = LiveCaptionState(error = "Audio input is busy")
            return
        }
        captureLease = lease
        generation += 1
        this.sourceLanguage = sourceLanguage
        this.targetLanguage = targetLanguage.ifBlank { "English" }
        this.translate = translate
        mutableState.value = LiveCaptionState(
            enabled = true,
            sourceLanguage = sourceLanguage,
            targetLanguage = this.targetLanguage.takeIf { translate },
        )
        startCycle(generation)
    }

    fun updateTranslationConfig(targetLanguage: String, translate: Boolean) {
        this.targetLanguage = targetLanguage.ifBlank { "English" }
        this.translate = translate
        mutableState.value = mutableState.value.copy(
            targetLanguage = this.targetLanguage.takeIf { translate },
            translatedText = if (translate) mutableState.value.translatedText else null,
        )
    }

    fun stop() {
        generation += 1
        cycleJob?.cancel()
        cycleJob = null
        recognition.stopListening()
        recognition.onPartialResult = null
        recognition.onSpeechStopped = null
        captureLease?.let(audioSessionCoordinator::release)
        captureLease = null
        mutableState.value = LiveCaptionState(enabled = false)
    }

    private fun startCycle(activeGeneration: Long) {
        val lease = captureLease
        if (!mutableState.value.enabled || generation != activeGeneration ||
            lease == null || !audioSessionCoordinator.isCurrent(lease)
        ) return
        recognition.onPartialResult = { partial ->
            if (generation == activeGeneration) {
                mutableState.value = mutableState.value.copy(
                    sourceText = partial,
                    translatedText = null,
                    error = null,
                )
            }
        }
        recognition.startListening(sourceLanguage) { result ->
            if (generation != activeGeneration || !mutableState.value.enabled) return@startListening
            val text = when (result) {
                is VoiceCommandHandler.VoiceResult.Text -> result.text.trim()
                is VoiceCommandHandler.VoiceResult.Command -> result.command.trim()
                is VoiceCommandHandler.VoiceResult.Error -> {
                    mutableState.value = mutableState.value.copy(error = result.message)
                    scheduleRestart(activeGeneration, 1_200L)
                    return@startListening
                }
            }
            if (text.isBlank()) {
                scheduleRestart(activeGeneration, 500L)
                return@startListening
            }
            mutableState.value = mutableState.value.copy(sourceText = text, translatedText = null, error = null)
            cycleJob = scope.launch {
                if (translate) {
                    translator.translate(recognition.getOpenAIApiKey(), text, targetLanguage)
                        .onSuccess { translated ->
                            if (generation == activeGeneration) {
                                mutableState.value = mutableState.value.copy(translatedText = translated)
                            }
                        }
                        .onFailure { error ->
                            if (generation == activeGeneration) {
                                mutableState.value = mutableState.value.copy(error = error.message ?: "Translation failed")
                            }
                        }
                }
                delay(1_500L)
                startCycle(activeGeneration)
            }
        }
    }

    private fun scheduleRestart(activeGeneration: Long, delayMs: Long) {
        cycleJob?.cancel()
        cycleJob = scope.launch {
            delay(delayMs)
            startCycle(activeGeneration)
        }
    }

    fun cleanup() {
        stop()
        recognition.cleanup()
        scope.coroutineContext[Job]?.cancel()
    }
}
