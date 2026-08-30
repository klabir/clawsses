package com.clawsses.phone.runtime

import android.content.Context
import android.os.SystemClock
import com.clawsses.phone.audio.AudioSessionCoordinator
import com.clawsses.phone.audio.AudioSessionLease
import com.clawsses.phone.openclaw.OpenClawClient
import com.clawsses.phone.talk.TalkModeManager
import com.clawsses.phone.tts.TtsPlaybackManager
import com.clawsses.phone.tts.TtsPlaybackState
import com.clawsses.phone.util.SecurePreferences
import com.clawsses.phone.voice.LiveCaptionManager
import com.clawsses.phone.voice.LocalWakeWordBlockers
import com.clawsses.phone.voice.LocalWakeWordPhase
import com.clawsses.phone.voice.LocalWakeWordStatus
import com.clawsses.phone.voice.SherpaLocalWakeWordDetector
import com.clawsses.phone.voice.VoiceCommandHandler
import com.clawsses.phone.voice.VoiceInputMode
import com.clawsses.phone.voice.VoiceLanguageManager
import com.clawsses.phone.voice.VoiceRecognitionManager
import com.clawsses.phone.voice.shouldRunLocalWakeWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-scoped policy owner for experimental local wake-word validation. Foreground capture,
 * Talk Mode, captions, TTS, and active OpenClaw runs always preempt the low-priority KWS lease.
 */
internal class LocalWakeWordRuntimeCoordinator(
    context: Context,
    private val audioSessions: AudioSessionCoordinator,
    private val voiceRecognition: VoiceRecognitionManager,
    private val voiceLanguage: VoiceLanguageManager,
    private val talkMode: TalkModeManager,
    private val liveCaptions: LiveCaptionManager,
    private val ttsPlayback: TtsPlaybackManager,
    private val openClaw: OpenClawClient,
    private val detector: SherpaLocalWakeWordDetector = SherpaLocalWakeWordDetector(context),
) {
    private val prefs = SecurePreferences.create(context, PREFS_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    private val mutableStatus = MutableStateFlow(
        LocalWakeWordStatus(enabled = enabled.value),
    )
    val status: StateFlow<LocalWakeWordStatus> = mutableStatus.asStateFlow()

    private var wakeLease: AudioSessionLease? = null
    private var activationLease: AudioSessionLease? = null
    private var activationActive = false
    private var faulted = false
    private val observers = mutableListOf<Job>()

    fun start() {
        if (observers.isNotEmpty()) return
        observers += scope.launch { enabled.collect { reconcile() } }
        observers += scope.launch { audioSessions.activeOwner.collect { reconcile() } }
        observers += scope.launch { voiceRecognition.isListening.collect { reconcile() } }
        observers += scope.launch { talkMode.state.collect { reconcile() } }
        observers += scope.launch { liveCaptions.state.collect { reconcile() } }
        observers += scope.launch { ttsPlayback.state.collect { reconcile() } }
        observers += scope.launch { openClaw.runState.collect { reconcile() } }
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        faulted = false
        enabled.value = value
        if (!value) cancelActivation()
        reconcile()
    }

    fun cleanup() {
        observers.forEach(Job::cancel)
        observers.clear()
        cancelActivation()
        stopWakeCapture()
        detector.destroy()
        scope.cancel()
    }

    private fun reconcile() {
        val currentEnabled = enabled.value
        val blockers = LocalWakeWordBlockers(
            foregroundCaptureActive = voiceRecognition.isListening.value,
            talkModeActive = talkMode.state.value.enabled,
            liveCaptionsActive = liveCaptions.state.value.enabled,
            playbackActive = ttsPlayback.state.value != TtsPlaybackState.IDLE,
            openClawRunActive = openClaw.runState.value !in setOf(
                OpenClawClient.RunState.IDLE,
                OpenClawClient.RunState.ERROR,
            ),
            activationActive = activationActive,
        )
        if (shouldRunLocalWakeWord(currentEnabled, faulted, blockers)) {
            startWakeCapture()
            return
        }

        stopWakeCapture()
        mutableStatus.value = mutableStatus.value.copy(
            enabled = currentEnabled,
            phase = when {
                !currentEnabled -> LocalWakeWordPhase.DISABLED
                faulted -> LocalWakeWordPhase.ERROR
                activationActive -> LocalWakeWordPhase.ACTIVATING
                else -> LocalWakeWordPhase.PAUSED
            },
        )
    }

    private fun startWakeCapture() {
        if (wakeLease != null) return
        val lease = audioSessions.beginWakeWord {
            scope.launch {
                wakeLease = null
                detector.stop()
                reconcile()
            }
        } ?: run {
            mutableStatus.value = mutableStatus.value.copy(
                enabled = true,
                phase = LocalWakeWordPhase.PAUSED,
            )
            return
        }
        wakeLease = lease
        mutableStatus.value = mutableStatus.value.copy(
            enabled = true,
            phase = LocalWakeWordPhase.STARTING,
            error = null,
        )
        val started = detector.start(
            onDetected = ::onDetected,
            onError = ::onDetectorError,
        )
        if (!started) {
            audioSessions.release(lease)
            wakeLease = null
            mutableStatus.value = mutableStatus.value.copy(phase = LocalWakeWordPhase.PAUSED)
            return
        }
        mutableStatus.value = mutableStatus.value.copy(phase = LocalWakeWordPhase.LISTENING)
    }

    private fun stopWakeCapture() {
        detector.stop()
        wakeLease?.let(audioSessions::release)
        wakeLease = null
    }

    private fun onDetected(keyword: String) {
        val normalized = keyword.trim().ifEmpty { DEFAULT_KEYWORD }
        stopWakeCapture()
        activationActive = true
        mutableStatus.value = mutableStatus.value.copy(
            phase = LocalWakeWordPhase.ACTIVATING,
            keyword = normalized,
            detectionCount = mutableStatus.value.detectionCount + 1L,
            lastDetectedAtMs = SystemClock.elapsedRealtime(),
            error = null,
        )
        val lease = audioSessions.beginCapture()
        if (lease == null) {
            activationActive = false
            mutableStatus.value = mutableStatus.value.copy(error = "Audio input is busy")
            reconcile()
            return
        }
        activationLease = lease
        voiceRecognition.onSpeechStopped = {
            if (activationActive) {
                mutableStatus.value = mutableStatus.value.copy(
                    phase = LocalWakeWordPhase.ACTIVATING,
                )
            }
        }
        voiceRecognition.startListening(
            languageTag = voiceLanguage.getActiveLanguageTag(),
            inputMode = VoiceInputMode.REALTIME,
        ) { result -> completeActivation(result) }
    }

    private fun completeActivation(result: VoiceCommandHandler.VoiceResult) {
        if (!activationActive) return
        activationActive = false
        voiceRecognition.onSpeechStopped = null
        activationLease?.let(audioSessions::release)
        activationLease = null
        when (result) {
            is VoiceCommandHandler.VoiceResult.Text -> {
                result.text.trim().takeIf(String::isNotEmpty)?.let(openClaw::sendMessage)
            }
            is VoiceCommandHandler.VoiceResult.Command -> Unit
            is VoiceCommandHandler.VoiceResult.Error -> {
                mutableStatus.value = mutableStatus.value.copy(error = result.message)
            }
        }
        reconcile()
    }

    private fun cancelActivation() {
        if (!activationActive) return
        activationActive = false
        voiceRecognition.cancelListening()
        voiceRecognition.onSpeechStopped = null
        activationLease?.let(audioSessions::release)
        activationLease = null
    }

    private fun onDetectorError(message: String) {
        stopWakeCapture()
        faulted = true
        mutableStatus.value = mutableStatus.value.copy(
            enabled = enabled.value,
            phase = LocalWakeWordPhase.ERROR,
            error = message,
        )
    }

    private companion object {
        const val PREFS_NAME = "clawsses"
        const val KEY_ENABLED = "experimental_local_wake_word_enabled"
        const val DEFAULT_KEYWORD = "HEY CLAWSSES"
    }
}
