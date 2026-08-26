package com.clawsses.phone.runtime

import android.content.Context
import android.util.Log
import com.clawsses.phone.glasses.GlassesConnectionManager
import com.clawsses.phone.glasses.RokidSdkManager
import com.clawsses.phone.glasses.WakeSignalManager
import com.clawsses.phone.openclaw.OpenClawClient
import com.clawsses.phone.service.GlassesConnectionService
import com.clawsses.phone.talk.TalkModeManager
import com.clawsses.phone.talk.TalkModePhase
import com.clawsses.phone.talk.TalkModeSource
import com.clawsses.phone.talk.TalkModeTransitions
import com.clawsses.phone.tts.TtsPlaybackManager
import com.clawsses.phone.tts.TtsPlaybackState
import com.clawsses.phone.tts.blocksVoiceCapture
import com.clawsses.phone.util.SecurePreferences
import com.clawsses.phone.voice.VoiceCommandHandler
import com.clawsses.phone.voice.VoiceLanguageManager
import com.clawsses.phone.voice.VoiceRecognitionManager
import com.clawsses.shared.TalkModeStateUpdate
import com.clawsses.shared.TtsVoiceCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Owns durable Talk/TTS/CXR transitions independently of Activity and Compose lifecycles. */
class TalkRuntimeCoordinator(
    private val context: Context,
    private val glassesManager: GlassesConnectionManager,
    private val openClawClient: OpenClawClient,
    private val voiceHandler: VoiceCommandHandler,
    private val voiceLanguageManager: VoiceLanguageManager,
    private val voiceRecognitionManager: VoiceRecognitionManager,
    private val talkModeManager: TalkModeManager,
    private val ttsPlaybackManager: TtsPlaybackManager,
    private val pendingPhotos: MutableStateFlow<List<String>>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)
    private var restartJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        voiceHandler.initialize()
        voiceLanguageManager.queryAvailableLanguages()
        configurePartialResults()
        glassesManager.tryAutoReconnectOnStartup()
        restoreGatewayConnection()
        observeConnectionCatalog()
        observeStandbyAndResume()
        observeGatewayReadiness()
        observeTtsPlayback()
        observeRunState()
        observeGlassesServiceLifetime()
        observeTalkStateSync()
    }

    fun startListening(source: TalkModeSource, interruptCurrent: Boolean) {
        if (TtsPlaybackManager.isPlaybackActive()) {
            Log.i(TAG, "Ignoring voice start while process-wide TTS is active")
            return
        }
        cancelRestart()
        val current = talkModeManager.state.value
        if (!current.enabled) return

        if (openClawClient.connectionState.value !is OpenClawClient.ConnectionState.Connected ||
            (source == TalkModeSource.GLASSES &&
                glassesManager.connectionState.value !is GlassesConnectionManager.ConnectionState.Connected)
        ) {
            talkModeManager.setPhase(TalkModePhase.DISCONNECTED)
            syncTalkModeStateToGlasses()
            return
        }

        if (source == TalkModeSource.GLASSES &&
            glassesManager.wakeSignalManager.wakeState.value !is WakeSignalManager.WakeState.Awake
        ) {
            voiceRecognitionManager.cancelListening()
            RokidSdkManager.clearCommunicationDevice()
            talkModeManager.pauseForStandby()
            return
        }

        if (interruptCurrent) {
            talkModeManager.setPhase(TalkModePhase.ABORTING)
            ttsPlaybackManager.stop()
            openClawClient.abortActiveRun()
        }

        voiceRecognitionManager.stopListening()
        val cycleId = talkModeManager.beginListening(source)
        val mode = if (voiceRecognitionManager.isOpenAIAvailable()) "openai" else "device"
        if (source == TalkModeSource.GLASSES) {
            RokidSdkManager.setCommunicationDevice()
            RokidSdkManager.sendAsrContent("...")
            sendVoiceState("listening", mode)
        }

        voiceRecognitionManager.onSpeechStopped = {
            val latest = talkModeManager.state.value
            if (latest.enabled && latest.cycleId == cycleId) {
                talkModeManager.setPhase(TalkModePhase.TRANSCRIBING, cycleId)
                if (source == TalkModeSource.GLASSES) sendVoiceState("processing", mode)
            }
        }

        voiceRecognitionManager.startListening(
            languageTag = voiceLanguageManager.getActiveLanguageTag(),
        ) { result -> handleRecognitionResult(source, cycleId, result) }
    }

    fun scheduleRestart(delayMs: Long) {
        cancelRestart()
        restartJob = scope.launch {
            delay(delayMs)
            val state = talkModeManager.state.value
            if (!state.enabled || TtsPlaybackManager.isPlaybackActive() ||
                ttsPlaybackManager.state.value.blocksVoiceCapture()
            ) return@launch
            if (state.source == TalkModeSource.GLASSES &&
                glassesManager.wakeSignalManager.wakeState.value !is WakeSignalManager.WakeState.Awake
            ) {
                voiceRecognitionManager.cancelListening()
                RokidSdkManager.clearCommunicationDevice()
                talkModeManager.pauseForStandby()
                return@launch
            }
            startListening(state.source, false)
        }
    }

    fun stopTalkMode(disable: Boolean) {
        cancelRestart()
        voiceRecognitionManager.stopListening()
        voiceRecognitionManager.onSpeechStopped = null
        ttsPlaybackManager.stop()
        openClawClient.abortActiveRun()
        RokidSdkManager.clearCommunicationDevice()
        if (disable) talkModeManager.setEnabled(false) else talkModeManager.resetToIdle()
        sendVoiceState("idle")
        syncTalkModeStateToGlasses()
    }

    fun prepareTtsPlayback() {
        cancelRestart()
        voiceRecognitionManager.stopListening()
        talkModeManager.beginSpeaking()
        val requireGlassesOutput = shouldRequireGlassesMediaOutput(
            glassesManager.connectionState.value is GlassesConnectionManager.ConnectionState.Connected,
        )
        ttsPlaybackManager.prepareOutput(requireBluetoothOutput = requireGlassesOutput)
        if (requireGlassesOutput) {
            RokidSdkManager.setCommunicationDevice()
            Log.i(TAG, "Keeping Rokid SCO communication route for TTS output")
        } else {
            RokidSdkManager.clearCommunicationDevice()
        }
    }

    fun stopCurrentTtsOutput() {
        Log.i(TAG, "Stopping current TTS output by voice command")
        ttsPlaybackManager.stop()
    }

    fun syncTalkModeStateToGlasses() {
        val state = talkModeManager.state.value
        if (state.phase == TalkModePhase.STANDBY &&
            glassesManager.wakeSignalManager.wakeState.value !is WakeSignalManager.WakeState.Awake
        ) return
        if (glassesManager.connectionState.value is GlassesConnectionManager.ConnectionState.Connected) {
            glassesManager.sendRawMessage(
                TalkModeStateUpdate(
                    enabled = state.enabled,
                    phase = state.phase.name.lowercase(),
                    interruptible = state.interruptible,
                    error = state.error,
                ).toJson(),
            )
        }
    }

    fun cleanup() {
        cancelRestart()
        scope.cancel()
    }

    private fun handleRecognitionResult(
        source: TalkModeSource,
        cycleId: Long,
        result: VoiceCommandHandler.VoiceResult,
    ) {
        val latest = talkModeManager.state.value
        if (!latest.enabled || latest.cycleId != cycleId) return
        if (source == TalkModeSource.GLASSES) RokidSdkManager.clearCommunicationDevice()
        when (result) {
            is VoiceCommandHandler.VoiceResult.Text -> handleRecognizedText(source, cycleId, result.text)
            is VoiceCommandHandler.VoiceResult.Command -> handleRecognizedCommand(result.command)
            is VoiceCommandHandler.VoiceResult.Error -> {
                if (source == TalkModeSource.GLASSES) {
                    RokidSdkManager.notifyAsrError()
                    sendVoiceState("error")
                }
                talkModeManager.setPhase(TalkModePhase.ERROR, cycleId, result.message)
                scheduleRestart(1_500L)
            }
        }
        syncTalkModeStateToGlasses()
    }

    private fun handleRecognizedText(source: TalkModeSource, cycleId: Long, rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) {
            if (source == TalkModeSource.GLASSES) {
                RokidSdkManager.notifyAsrNone()
                sendVoiceState("idle")
            }
            talkModeManager.resetToIdle()
            scheduleRestart(800L)
            return
        }
        if (source == TalkModeSource.GLASSES) {
            RokidSdkManager.sendAsrContent(text)
            RokidSdkManager.notifyAsrEnd()
            glassesManager.sendRawMessage(
                JSONObject().apply {
                    put("type", "voice_result")
                    put("result_type", "text")
                    put("text", text)
                    put("autoSent", true)
                }.toString(),
            )
            scope.launch {
                delay(500L)
                RokidSdkManager.sendExitEvent()
            }
        }
        talkModeManager.setPhase(TalkModePhase.SENDING, cycleId)
        scope.launch {
            val runBusy = openClawClient.runState.value !in setOf(
                OpenClawClient.RunState.IDLE,
                OpenClawClient.RunState.ERROR,
            )
            if (runBusy) openClawClient.abortActiveRun()
            val ready = withTimeoutOrNull(12_000L) {
                openClawClient.runState.first {
                    it == OpenClawClient.RunState.IDLE || it == OpenClawClient.RunState.ERROR
                }
            } != null
            val stateBeforeSend = talkModeManager.state.value
            if (!stateBeforeSend.enabled || stateBeforeSend.cycleId != cycleId) return@launch
            if (!ready) {
                talkModeManager.setPhase(TalkModePhase.ERROR, cycleId, "Previous run did not stop")
                scheduleRestart(1_500L)
                return@launch
            }
            val images = pendingPhotos.value.ifEmpty { null }
            talkModeManager.setPhase(TalkModePhase.WAITING, cycleId)
            openClawClient.sendMessage(text, images)
            if (images != null) {
                pendingPhotos.value = emptyList()
                glassesManager.sendRawMessage("""{"type":"remove_photo","all":true}""")
            }
        }
    }

    private fun handleRecognizedCommand(rawCommand: String) {
        val command = rawCommand.lowercase()
        when {
            TtsVoiceCommands.isStopCurrentOutput(command) -> {
                stopCurrentTtsOutput()
                sendAutoCommand(TtsVoiceCommands.STOP_CURRENT_OUTPUT)
                talkModeManager.resetToIdle()
                scheduleRestart(600L)
            }
            command in setOf("stop talk mode", "talk mode off", "talk modus stoppen", "talk modus aus") -> {
                stopTalkMode(disable = true)
            }
            else -> {
                sendAutoCommand(rawCommand)
                talkModeManager.resetToIdle()
                scheduleRestart(600L)
            }
        }
    }

    private fun sendAutoCommand(command: String) {
        if (glassesManager.connectionState.value !is GlassesConnectionManager.ConnectionState.Connected) return
        glassesManager.sendRawMessage(
            JSONObject().apply {
                put("type", "voice_result")
                put("result_type", "command")
                put("text", command)
                put("autoSent", true)
            }.toString(),
        )
    }

    private fun configurePartialResults() {
        val forward: (String) -> Unit = { partialText ->
            RokidSdkManager.sendAsrContent(partialText)
            sendVoiceState("recognizing", text = partialText)
        }
        voiceHandler.onPartialResult = forward
        voiceRecognitionManager.onPartialResult = forward
    }

    private fun observeConnectionCatalog() {
        scope.launch {
            openClawClient.connectionState.collect { state ->
                if (state is OpenClawClient.ConnectionState.Connected) {
                    openClawClient.requestSessions()
                    openClawClient.requestAgents()
                    openClawClient.requestModels()
                } else if (talkModeManager.state.value.enabled) {
                    talkModeManager.setPhase(TalkModePhase.DISCONNECTED)
                }
            }
        }
    }

    private fun observeStandbyAndResume() {
        scope.launch {
            combine(glassesManager.wakeSignalManager.wakeState, talkModeManager.state) { wake, talk ->
                wake to talk
            }.collect { (wake, state) ->
                val awake = wake is WakeSignalManager.WakeState.Awake
                when {
                    TalkModeTransitions.shouldPauseForStandby(state, awake) -> {
                        Log.i(TAG, "Pausing glasses Talk Mode for standby")
                        cancelRestart()
                        talkModeManager.pauseForStandby()
                        voiceRecognitionManager.cancelListening()
                        voiceRecognitionManager.onSpeechStopped = null
                        RokidSdkManager.clearCommunicationDevice()
                    }
                    TalkModeTransitions.shouldResumeFromStandby(state, awake) -> {
                        delay(700L)
                        val latest = talkModeManager.state.value
                        if (latest.phase == TalkModePhase.STANDBY &&
                            glassesManager.wakeSignalManager.wakeState.value is WakeSignalManager.WakeState.Awake
                        ) startListening(TalkModeSource.GLASSES, false)
                    }
                }
            }
        }
    }

    private fun observeGatewayReadiness() {
        scope.launch {
            combine(
                openClawClient.connectionState,
                glassesManager.connectionState,
                talkModeManager.state,
            ) { gateway, glasses, talk -> Triple(gateway, glasses, talk) }
                .collect { (gateway, glasses, talk) ->
                    val sourceReady = talk.source != TalkModeSource.GLASSES ||
                        glasses is GlassesConnectionManager.ConnectionState.Connected
                    if (talk.enabled && talk.phase == TalkModePhase.DISCONNECTED &&
                        gateway is OpenClawClient.ConnectionState.Connected && sourceReady
                    ) startListening(talk.source, false)
                }
        }
    }

    private fun observeTtsPlayback() {
        scope.launch {
            ttsPlaybackManager.state.collect { playback ->
                val talk = talkModeManager.state.value
                if (!talk.enabled) return@collect
                when (playback) {
                    TtsPlaybackState.SYNTHESIZING, TtsPlaybackState.PLAYING -> {
                        prepareTtsPlayback()
                        talkModeManager.setPhase(TalkModePhase.SPEAKING)
                    }
                    TtsPlaybackState.IDLE -> if (talk.phase == TalkModePhase.SPEAKING) {
                        talkModeManager.resetToIdle()
                        scheduleRestart(450L)
                    }
                    TtsPlaybackState.ERROR -> if (talk.phase == TalkModePhase.SPEAKING) {
                        talkModeManager.setPhase(TalkModePhase.ERROR, error = "Voice playback failed")
                        scheduleRestart(1_500L)
                    }
                }
            }
        }
    }

    private fun observeRunState() {
        scope.launch {
            combine(openClawClient.runState, openClawClient.runError) { run, error -> run to error }
                .collect { (run, error) ->
                    val talk = talkModeManager.state.value
                    if (!talk.enabled) return@collect
                    when (run) {
                        OpenClawClient.RunState.WAITING,
                        OpenClawClient.RunState.REASONING,
                        OpenClawClient.RunState.STREAMING -> if (talk.phase !in setOf(
                            TalkModePhase.LISTENING,
                            TalkModePhase.TRANSCRIBING,
                            TalkModePhase.SENDING,
                        )) talkModeManager.setPhase(TalkModePhase.WAITING)
                        OpenClawClient.RunState.ABORTING -> if (talk.phase != TalkModePhase.LISTENING) {
                            talkModeManager.setPhase(TalkModePhase.ABORTING)
                        }
                        OpenClawClient.RunState.ERROR -> if (talk.phase == TalkModePhase.WAITING) {
                            talkModeManager.setPhase(TalkModePhase.ERROR, error = error)
                            scheduleRestart(1_500L)
                        }
                        else -> Unit
                    }
                }
        }
    }

    private fun observeGlassesServiceLifetime() {
        scope.launch {
            glassesManager.connectionState.collect { state ->
                when (state) {
                    is GlassesConnectionManager.ConnectionState.Connected,
                    is GlassesConnectionManager.ConnectionState.Reconnecting -> {
                        GlassesConnectionService.start(context)
                    }
                    is GlassesConnectionManager.ConnectionState.Disconnected -> {
                        val talk = talkModeManager.state.value
                        if (talk.enabled && talk.source == TalkModeSource.GLASSES) {
                            cancelRestart()
                            voiceRecognitionManager.stopListening()
                            RokidSdkManager.clearCommunicationDevice()
                            talkModeManager.setPhase(TalkModePhase.DISCONNECTED)
                        }
                        if (!RokidSdkManager.hasSavedConnectionInfo()) {
                            GlassesConnectionService.stop(context)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun observeTalkStateSync() {
        scope.launch {
            combine(talkModeManager.state, glassesManager.connectionState) { talk, glasses -> talk to glasses }
                .collect { syncTalkModeStateToGlasses() }
        }
    }

    private fun restoreGatewayConnection() {
        val prefs = SecurePreferences.create(context, PREFS_NAME)
        val host = prefs.getString("openclaw_host", "10.0.2.2").orEmpty()
        if (host.isNotBlank()) {
            openClawClient.connect(
                host,
                prefs.getString("openclaw_port", "18789")?.toIntOrNull() ?: 18789,
                prefs.getString("openclaw_token", "").orEmpty(),
            )
        }
    }

    private fun sendVoiceState(state: String, mode: String? = null, text: String? = null) {
        if (glassesManager.connectionState.value !is GlassesConnectionManager.ConnectionState.Connected) return
        glassesManager.sendRawMessage(
            JSONObject().apply {
                put("type", "voice_state")
                put("state", state)
                if (mode != null) put("mode", mode)
                if (text != null) put("text", text)
            }.toString(),
        )
    }

    private fun cancelRestart() {
        restartJob?.cancel()
        restartJob = null
    }

    companion object {
        private const val TAG = "TalkRuntime"
        private const val PREFS_NAME = "clawsses"
    }
}

internal fun shouldRequireGlassesMediaOutput(glassesConnected: Boolean): Boolean = glassesConnected
