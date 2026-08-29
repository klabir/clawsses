package com.clawsses.phone.runtime

import android.content.Context
import android.util.Base64
import android.util.Log
import com.rokid.cxr.client.utils.ValueUtil
import com.clawsses.phone.BuildConfig
import com.clawsses.phone.glasses.CxrOutboundTransport
import com.clawsses.phone.glasses.GlassesConnectionManager
import com.clawsses.phone.glasses.RokidDeviceFacade
import com.clawsses.phone.glasses.ProductionRokidDeviceFacade
import com.clawsses.phone.glasses.WakeSignalManager
import com.clawsses.phone.media.ImagePipeline
import com.clawsses.phone.media.MediaStoreSaver
import com.clawsses.phone.media.PendingPhotoRepository
import com.clawsses.phone.media.ChatAttachmentFileStore
import com.clawsses.phone.notifications.NotificationRelay
import com.clawsses.phone.openclaw.GlassesChatHistoryPage
import com.clawsses.phone.openclaw.OpenClawClient
import com.clawsses.phone.openclaw.buildGlassesModelPage
import com.clawsses.phone.openclaw.resolveGlassesModelSelection
import com.clawsses.phone.talk.TalkModeManager
import com.clawsses.phone.talk.TalkModePhase
import com.clawsses.phone.talk.TalkModeSource
import com.clawsses.phone.talk.TalkRestartReason
import com.clawsses.phone.tts.TtsPlaybackManager
import com.clawsses.phone.tts.TtsSettingsManager
import com.clawsses.phone.tts.blocksVoiceCapture
import com.clawsses.phone.util.SecurePreferences
import com.clawsses.phone.voice.LiveCaptionManager
import com.clawsses.phone.voice.LiveCaptionState
import com.clawsses.phone.voice.VoiceLanguageManager
import com.clawsses.phone.voice.VoiceRecognitionManager
import com.clawsses.shared.AgentProgressUpdate
import com.clawsses.shared.ChatMessage
import com.clawsses.shared.ConnectionUpdate
import com.clawsses.shared.CxrPayloadLimits
import com.clawsses.shared.GlassesCommand
import com.clawsses.shared.GlassesCommandCodec
import com.clawsses.shared.GlassesCommandDecodeResult
import com.clawsses.shared.HudCard
import com.clawsses.shared.HudCardAction
import com.clawsses.shared.HudChatMessage
import com.clawsses.shared.HudThumbnailAttachment
import com.clawsses.shared.LiveCaptionUpdate
import com.clawsses.shared.ModelOperationUpdate
import com.clawsses.shared.PeerDescriptor
import com.clawsses.shared.PeerProtocol
import com.clawsses.shared.PhonePeerState
import com.clawsses.shared.PhotoResult
import com.clawsses.shared.RunStateUpdate
import com.clawsses.shared.SessionOperationUpdate
import com.clawsses.shared.TtsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped protocol owner between OpenClaw, the phone runtime, and the glasses HUD.
 *
 * No callback in this class depends on Activity or Compose lifetime. Recreating the phone UI can no
 * longer replace the SDK callbacks, lose a glasses command, or start a second voice capture cycle.
 */
class PhoneGlassesBridgeController(
    context: Context,
    private val glassesManager: GlassesConnectionManager,
    private val openClawClient: OpenClawClient,
    private val voiceLanguageManager: VoiceLanguageManager,
    private val voiceRecognitionManager: VoiceRecognitionManager,
    private val liveCaptionManager: LiveCaptionManager,
    private val talkModeManager: TalkModeManager,
    private val ttsSettingsManager: TtsSettingsManager,
    private val ttsPlaybackManager: TtsPlaybackManager,
    private val pendingPhotoRepository: PendingPhotoRepository,
    private val chatAttachmentFileStore: ChatAttachmentFileStore,
    private val talkCoordinator: TalkRuntimeCoordinator,
    private val stagedVoiceCoordinator: StagedVoiceCoordinator,
    private val rokidDevice: RokidDeviceFacade = ProductionRokidDeviceFacade,
) {
    private val appContext = context.applicationContext
    private val prefs = SecurePreferences.create(appContext, PREFS_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)
    private val activationPending = AtomicBoolean(false)
    private val photoCaptureGate = PhotoCaptureAttemptGate()
    private val hudCardBodies = LinkedHashMap<String, String>()
    private var pendingHistoryBeforeMessageId: String? = null
    private var photoCaptureTimeoutJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        wireOpenClawCallbacks()
        wireGlassesCallbacks()
        observeRuntimeState()
        Log.i(TAG, "Process bridge started")
    }

    fun setLiveCaptionsEnabled(enabled: Boolean) {
        if (enabled) {
            if (talkModeManager.state.value.enabled) talkCoordinator.stopTalkMode(disable = true)
            stagedVoiceCoordinator.cancel(sendIdle = false)
            rokidDevice.setCommunicationDevice()
            liveCaptionManager.start(
                sourceLanguage = voiceLanguageManager.getActiveLanguageTag(),
                targetLanguage = prefs.getString(KEY_CAPTION_TARGET_LANGUAGE, "English") ?: "English",
                translate = prefs.getBoolean(KEY_TRANSLATE_CAPTIONS, false),
            )
        } else {
            liveCaptionManager.stop()
            rokidDevice.clearCommunicationDevice()
        }
    }

    fun capturePhoto(sendAfterCapture: Boolean, visionPrompt: String? = null) {
        scope.launch {
            when (val begin = photoCaptureGate.begin()) {
                PhotoCaptureAttemptGate.BeginResult.Busy -> {
                    Log.w(TAG, "Ignoring overlapping photo capture")
                    sendPhotoError("busy")
                }
                is PhotoCaptureAttemptGate.BeginResult.Started -> {
                    beginPhotoCapture(begin.attemptId, sendAfterCapture, visionPrompt)
                }
            }
        }
    }

    fun cleanup() {
        photoCaptureTimeoutJob?.cancel()
        rokidDevice.onPhotoResult = null
        stagedVoiceCoordinator.cancel(sendIdle = false)
        clearCallbacks()
        scope.cancel()
    }

    private fun observeRuntimeState() {
        scope.launch {
            combine(liveCaptionManager.state, glassesManager.connectionState) { caption, glasses ->
                caption to glasses
            }.collect { (caption, glasses) ->
                if (glasses is GlassesConnectionManager.ConnectionState.Connected) {
                    glassesManager.sendRawMessage(caption.toProtocol().toJson())
                }
            }
        }
        scope.launch {
            combine(
                ttsSettingsManager.isEnabled,
                ttsSettingsManager.selectedVoiceName,
                ttsSettingsManager.provider,
                ttsPlaybackManager.state,
                ttsPlaybackManager.canReplay,
            ) { enabled, voice, provider, playback, canReplay ->
                TtsState(
                    enabled = enabled,
                    voiceName = voice,
                    provider = provider.name.lowercase(),
                    playbackState = playback.name.lowercase(),
                    canReplay = canReplay,
                )
            }.collect { state ->
                if (isGlassesConnected()) glassesManager.sendRawMessage(state.toJson())
            }
        }
        scope.launch {
            combine(
                openClawClient.runState,
                openClawClient.runError,
                glassesManager.connectionState,
            ) { run, error, glasses -> Triple(run, error, glasses) }
                .collect { (run, error, glasses) ->
                    if (glasses is GlassesConnectionManager.ConnectionState.Connected) {
                        glassesManager.sendRawMessage(run.toProtocol(error).toJson())
                    }
                }
        }
        scope.launch {
            glassesManager.connectionState.collect { state ->
                if (state is GlassesConnectionManager.ConnectionState.Connected) sendCompleteState()
            }
        }
        scope.launch {
            NotificationRelay.pending.collect { pending ->
                if (!isGlassesConnected()) return@collect
                pending.forEach { notification ->
                    sendHudCard(
                        HudCard(
                            id = notification.id,
                            source = notification.appName,
                            title = notification.title,
                            body = notification.body.take(420),
                            expiresAt = System.currentTimeMillis() + 90_000L,
                            actions = listOf(
                                HudCardAction("summarize", "Summarize"),
                                HudCardAction("dismiss", "Dismiss"),
                            ),
                        ),
                    )
                    NotificationRelay.consume(notification.id)
                }
            }
        }
    }

    private fun wireOpenClawCallbacks() {
        openClawClient.onChatMessage = { message ->
            val wakeState = glassesManager.wakeSignalManager.wakeState.value
            val isNewMessage = message.role == "assistant" &&
                wakeState !is WakeSignalManager.WakeState.Awake &&
                wakeState !is WakeSignalManager.WakeState.WakingUp
            val payload = buildGlassesChatMessageJson(message)
            if (CxrPayloadLimits.fits(payload)) {
                glassesManager.sendRawMessage(payload, isNewMessage = isNewMessage)
            } else {
                sendHistoryToGlasses(openClawClient.chatMessages.value, "oversized completed message")
            }
            if (isNewMessage && message.content.isNotBlank()) {
                sendHudCard(
                    HudCard(
                        id = "openclaw:${message.id}",
                        source = "OpenClaw",
                        title = "New assistant update",
                        body = message.content.take(420),
                        priority = "high",
                        expiresAt = System.currentTimeMillis() + 120_000L,
                        actions = listOf(
                            HudCardAction("reply", "Reply"),
                            HudCardAction("dismiss", "Dismiss"),
                        ),
                    ),
                )
            }
        }
        openClawClient.onChatHistory = { sendHistoryToGlasses(it, "history callback") }
        openClawClient.onAgentThinking = { message ->
            glassesManager.notifyStreamStart(message.id)
            glassesManager.sendRawMessage(message.toJson(), isStreamContent = true)
        }
        openClawClient.onAgentProgress = { update: AgentProgressUpdate ->
            glassesManager.sendRawMessage(update.toJson(), isStreamContent = true)
        }
        openClawClient.onChatStream = {
            glassesManager.sendRawMessage(it.toJson(), isStreamContent = true)
        }
        openClawClient.onChatStreamEnd = { message ->
            scope.launch {
                glassesManager.notifyStreamEnd(message.id)
                glassesManager.sendRawMessage(message.toJson())
                handleStreamEnd(message.id, message.state)
            }
        }
        openClawClient.onSessionList = { message ->
            val payload = message.toJson()
            if (CxrPayloadLimits.fits(payload)) {
                glassesManager.sendRawMessage(payload)
            } else {
                glassesManager.sendRawMessage(
                    SessionOperationUpdate(
                        operation = "list",
                        state = "error",
                        error = "Session page is too large",
                    ).toJson(),
                )
            }
        }
        openClawClient.onSessionOperation = { glassesManager.sendRawMessage(it.toJson()) }
        openClawClient.onAgentList = { glassesManager.sendRawMessage(it.toJson()) }
        openClawClient.onConnectionUpdate = { glassesManager.sendRawMessage(it.toJson()) }
        openClawClient.onMoreHistoryLoaded = { prependedCount, hasMore ->
            val beforeMessageId = pendingHistoryBeforeMessageId
            pendingHistoryBeforeMessageId = null
            if (beforeMessageId != null) {
                val page = GlassesChatHistoryPage.before(
                    openClawClient.chatMessages.value,
                    beforeMessageId,
                    hasMore,
                )
                sendHistoryPageToGlasses(
                    page ?: GlassesChatHistoryPage.Page(emptyList(), hasMore),
                    isLoadMore = true,
                    reason = "phone history expansion; prepended=$prependedCount, phoneHasMore=$hasMore",
                )
            }
        }
    }

    private fun wireGlassesCallbacks() {
        glassesManager.onAiKeyDown = ::activateGlassesVoice
        glassesManager.onAiExit = {
            val talk = talkModeManager.state.value
            val needsFallback = if (talk.enabled) {
                talk.phase in setOf(
                    TalkModePhase.IDLE,
                    TalkModePhase.STANDBY,
                    TalkModePhase.DISCONNECTED,
                    TalkModePhase.ERROR,
                )
            } else {
                !voiceRecognitionManager.isListening.value
            }
            if (needsFallback && !activationPending.get()) activateGlassesVoice()
        }
        glassesManager.onMessageFromGlasses = { raw ->
            scope.launch { handleGlassesMessage(raw) }
        }
    }

    private fun activateGlassesVoice() {
        if (!activationPending.compareAndSet(false, true)) return
        scope.launch {
            try {
                val interruptedTts = TtsPlaybackManager.isPlaybackActive() ||
                    ttsPlaybackManager.state.value.blocksVoiceCapture()
                if (interruptedTts) talkCoordinator.stopCurrentTtsOutput()
                if (liveCaptionManager.state.value.enabled) setLiveCaptionsEnabled(false)
                val talk = talkModeManager.state.value
                if (talk.enabled) {
                    talkCoordinator.startListening(
                        TalkModeSource.GLASSES,
                        !interruptedTts && talk.phase in setOf(
                            TalkModePhase.SENDING,
                            TalkModePhase.WAITING,
                            TalkModePhase.SPEAKING,
                            TalkModePhase.ABORTING,
                        ) || ttsPlaybackManager.state.value.blocksVoiceCapture(),
                    )
                } else {
                    stagedVoiceCoordinator.start()
                }
            } finally {
                activationPending.set(false)
            }
        }
    }

    private fun handleGlassesMessage(raw: String) {
        when (val result = GlassesCommandCodec.decode(raw)) {
            is GlassesCommandDecodeResult.Success -> when (val command = result.command) {
                is GlassesCommand.UserInput -> handleUserInput(command)
                GlassesCommand.StartVoice -> handleStartVoice()
                GlassesCommand.CancelVoice -> handleCancelVoice()
                is GlassesCommand.ListSessions -> openClawClient.requestSessionPage(command.offset)
                is GlassesCommand.SwitchSession -> openClawClient.switchSession(command.sessionKey)
                GlassesCommand.CreateSession -> openClawClient.createSession()
                GlassesCommand.ListAgents -> openClawClient.requestAgents()
                is GlassesCommand.SwitchAgent -> openClawClient.switchAgent(command.agentId, command.agentName)
                is GlassesCommand.ListModels -> requestModelPage(command.offset)
                is GlassesCommand.SelectModel -> selectModel(command)
                GlassesCommand.AbortRun -> {
                    ttsPlaybackManager.stop()
                    openClawClient.abortActiveRun()
                }
                is GlassesCommand.Slash -> openClawClient.sendSlashCommand(command.command)
                is GlassesCommand.RequestState -> handleStateRequest(command)
                is GlassesCommand.TtsToggle -> handleTtsToggle(command.enabled)
                is GlassesCommand.TtsControl -> handleTtsControl(command.action)
                is GlassesCommand.TalkModeToggle -> handleTalkModeToggle(command.enabled)
                is GlassesCommand.LiveCaptionToggle -> setLiveCaptionsEnabled(command.enabled)
                is GlassesCommand.HudCardAction -> handleHudCardAction(command)
                is GlassesCommand.TakePhoto -> capturePhoto(
                    command.sendAfterCapture,
                    command.visionPrompt,
                )
                is GlassesCommand.RemovePhoto -> removePhoto(command)
                is GlassesCommand.RequestMoreHistory -> requestMoreHistory(command.beforeMessageId)
                is GlassesCommand.TransportAck,
                is GlassesCommand.WakeAck -> Unit // Consumed by GlassesConnectionManager.
            }
            is GlassesCommandDecodeResult.UnknownType ->
                Log.w(TAG, "Ignoring unknown glasses message type=${result.type}")
            is GlassesCommandDecodeResult.Malformed ->
                Log.w(TAG, "Ignoring malformed glasses message type=${result.type}: ${result.reason}")
        }
    }

    private fun handleUserInput(command: GlassesCommand.UserInput) {
        scope.launch {
            val images = pendingPhotoRepository.consumeEncoded().ifEmpty { null }
            if (command.text.isNotEmpty() || images != null) {
                openClawClient.sendMessage(command.text, images, command.clientMessageId)
            }
        }
    }

    private fun handleStartVoice() {
        if (TtsPlaybackManager.isPlaybackActive() || ttsPlaybackManager.state.value.blocksVoiceCapture()) return
        if (liveCaptionManager.state.value.enabled) setLiveCaptionsEnabled(false)
        val talk = talkModeManager.state.value
        if (talk.enabled) {
            talkCoordinator.startListening(
                TalkModeSource.GLASSES,
                talk.interruptible || ttsPlaybackManager.state.value.blocksVoiceCapture(),
            )
        } else {
            stagedVoiceCoordinator.start()
        }
    }

    private fun handleCancelVoice() {
        if (talkModeManager.state.value.enabled) {
            talkCoordinator.stopTalkMode(disable = true)
        } else {
            stagedVoiceCoordinator.cancel()
        }
    }

    private fun requestModelPage(offset: Int) {
        scope.launch {
            if (openClawClient.modelList.value.isEmpty()) {
                openClawClient.requestModels()
                withTimeoutOrNull(5_000L) { openClawClient.modelList.first { it.isNotEmpty() } }
            }
            sendModelPageToGlasses(
                offset,
                if (openClawClient.modelList.value.isEmpty()) {
                    openClawClient.modelSelectionError.value ?: "No models available"
                } else null,
            )
        }
    }

    private fun selectModel(command: GlassesCommand.SelectModel) {
        val models = openClawClient.modelList.value
        val requestedSessionKey = command.sessionKey
        val selected = resolveGlassesModelSelection(
            models,
            command.catalog,
            command.index,
        )
        val validationError = when {
            requestedSessionKey.isBlank() || requestedSessionKey != openClawClient.currentSessionKey.value ->
                "Session changed; reopen Models"
            openClawClient.runState.value !in setOf(
                OpenClawClient.RunState.IDLE,
                OpenClawClient.RunState.ERROR,
            ) -> "Available after response"
            openClawClient.isSelectingModel.value -> "Model change already in progress"
            selected == null -> "Model list changed; reopen Models"
            else -> null
        }
        if (validationError != null || selected == null) {
            glassesManager.sendRawMessage(
                ModelOperationUpdate(state = "error", error = validationError ?: "Could not change model").toJson(),
            )
            return
        }
        glassesManager.sendRawMessage(ModelOperationUpdate(state = "loading").toJson())
        openClawClient.selectModel(selected)
        scope.launch {
            val completed = withTimeoutOrNull(8_000L) {
                openClawClient.isSelectingModel.first { !it }
                true
            } == true
            val selectedIndex = models.indexOfFirst { it.ref == selected.ref }
            val success = completed && openClawClient.currentModelRef.value == selected.ref &&
                openClawClient.currentSessionKey.value == requestedSessionKey
            glassesManager.sendRawMessage(
                if (success) {
                    ModelOperationUpdate(
                        state = "success",
                        currentIndex = selectedIndex,
                        currentName = selected.name,
                    )
                } else {
                    ModelOperationUpdate(
                        state = "error",
                        error = openClawClient.modelSelectionError.value
                            ?: if (!completed) "Model change timed out"
                            else "Session changed; verify the active model",
                    )
                }.toJson(),
            )
        }
    }

    private fun handleStateRequest(command: GlassesCommand.RequestState) {
        glassesManager.updatePeerDescriptor(
            PeerDescriptor(
                versionName = command.versionName,
                versionCode = command.versionCode?.takeIf { it >= 0 },
                protocolVersion = command.protocolVersion,
                capabilities = PeerProtocol.normalizeCapabilities(command.capabilities),
            ),
        )
        glassesManager.sendRawMessage(
            PhonePeerState(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
            ).toJson(),
        )
        sendCompleteState()
        openClawClient.requestModels()
    }

    private fun sendCompleteState() {
        if (!isGlassesConnected()) return
        val currentKey = openClawClient.currentSessionKey.value
        val currentName = currentKey?.let { key ->
            val agentId = openClawClient.agentIdFromSessionKey(key)
            openClawClient.agentList.value.firstOrNull { it.id == agentId }?.name
                ?: openClawClient.sessionList.value.firstOrNull { it.key == key }?.name
        }
        glassesManager.sendRawMessage(
            ConnectionUpdate(
                connected = openClawClient.connectionState.value is OpenClawClient.ConnectionState.Connected,
                sessionId = currentKey,
                sessionName = currentName,
            ).toJson(),
        )
        glassesManager.sendRawMessage(openClawClient.currentAgentListUpdate().toJson())
        sendHistoryToGlasses(openClawClient.chatMessages.value, "state snapshot")
        sendTtsState()
        glassesManager.sendRawMessage(
            openClawClient.runState.value.toProtocol(openClawClient.runError.value).toJson(),
        )
        talkCoordinator.syncTalkModeStateToGlasses()
        glassesManager.sendRawMessage(liveCaptionManager.state.value.toProtocol().toJson())
    }

    private fun handleTtsToggle(requested: Boolean) {
        val enabled = requested && ttsSettingsManager.isConfigured()
        ttsSettingsManager.setEnabled(enabled)
        sendTtsState()
    }

    private fun handleTtsControl(action: String) {
        when (action) {
            "stop" -> ttsPlaybackManager.stop()
            "replay" -> {
                talkCoordinator.prepareTtsPlayback()
                ttsPlaybackManager.replay()
            }
        }
    }

    private fun handleTalkModeToggle(enabled: Boolean) {
        if (enabled) {
            if (liveCaptionManager.state.value.enabled) setLiveCaptionsEnabled(false)
            talkModeManager.setEnabled(true, TalkModeSource.GLASSES)
            talkCoordinator.startListening(TalkModeSource.GLASSES, false)
        } else {
            talkCoordinator.stopTalkMode(disable = true)
        }
        talkCoordinator.syncTalkModeStateToGlasses()
    }

    private fun handleHudCardAction(command: GlassesCommand.HudCardAction) {
        when (command.actionId) {
            "summarize" -> hudCardBodies[command.cardId]?.let { body ->
                openClawClient.sendMessage(
                    "Summarize this notification concisely and identify any action needed: $body",
                )
            }
            "reply" -> stagedVoiceCoordinator.start()
        }
        hudCardBodies.remove(command.cardId)
    }

    private fun removePhoto(command: GlassesCommand.RemovePhoto) {
        scope.launch {
            val index = command.index
            when {
                command.all -> pendingPhotoRepository.clear()
                index != null -> pendingPhotoRepository.removeAt(index)
            }
        }
    }

    private fun handleStreamEnd(messageId: String, state: String) {
        val fullText = openClawClient.chatMessages.value.lastOrNull { it.id == messageId }?.content
        val talk = talkModeManager.state.value
        if (state == "final" && fullText != null) {
            if (talk.enabled) {
                if (ttsSettingsManager.isEnabled.value && ttsSettingsManager.isConfigured() && fullText.isNotBlank()) {
                    if (liveCaptionManager.state.value.enabled) setLiveCaptionsEnabled(false)
                    talkCoordinator.prepareTtsPlayback()
                    talkModeManager.setPhase(TalkModePhase.SPEAKING)
                    ttsPlaybackManager.onMessageComplete(fullText)
                } else {
                    talkModeManager.resetToIdle()
                    talkCoordinator.scheduleRestart(450L)
                }
            } else {
                if (ttsSettingsManager.isEnabled.value && ttsSettingsManager.isConfigured() && fullText.isNotBlank()) {
                    if (liveCaptionManager.state.value.enabled) setLiveCaptionsEnabled(false)
                    talkCoordinator.prepareTtsPlayback()
                }
                ttsPlaybackManager.onMessageComplete(fullText)
            }
        } else if (state != "final") {
            ttsPlaybackManager.stop()
            if (talk.enabled && talk.phase !in setOf(
                    TalkModePhase.LISTENING,
                    TalkModePhase.TRANSCRIBING,
                    TalkModePhase.SENDING,
                )
            ) {
                talkModeManager.resetToIdle()
                talkCoordinator.scheduleRestart(800L, TalkRestartReason.RECOGNITION_ERROR)
            }
        }
    }

    private fun sendModelPageToGlasses(offset: Int, error: String?) {
        val payload = buildGlassesModelPage(
            openClawClient.modelList.value,
            openClawClient.currentModelRef.value,
            offset,
            error,
        ).toJson()
        if (CxrPayloadLimits.byteSize(payload) + CxrOutboundTransport.ACK_METADATA_RESERVE_BYTES <=
            CxrPayloadLimits.MAX_BYTES
        ) {
            glassesManager.sendRawMessage(payload)
        } else {
            glassesManager.sendRawMessage(
                ModelOperationUpdate(state = "error", error = "Model page is too large").toJson(),
            )
        }
    }

    private fun sendHistoryToGlasses(messages: List<ChatMessage>, reason: String) {
        sendHistoryPageToGlasses(
            page = GlassesChatHistoryPage.latest(
                messages,
                gatewayHasMore = openClawClient.hasMoreHistory.value,
            ),
            isLoadMore = false,
            reason = reason,
        )
    }

    private fun requestMoreHistory(beforeMessageId: String?) {
        val id = beforeMessageId?.takeIf { it.isNotBlank() }
            ?: return sendHistoryPageToGlasses(
                GlassesChatHistoryPage.Page(emptyList(), hasMore = false),
                isLoadMore = true,
                reason = "missing history cursor",
            )
        val page = GlassesChatHistoryPage.before(
            openClawClient.chatMessages.value,
            id,
            openClawClient.hasMoreHistory.value,
        )
        if (page != null && page.messages.isNotEmpty()) {
            sendHistoryPageToGlasses(page, isLoadMore = true, reason = "cached history page")
            return
        }
        if (openClawClient.hasMoreHistory.value && !openClawClient.isLoadingMoreHistory.value) {
            pendingHistoryBeforeMessageId = id
            openClawClient.loadMoreHistory()
            return
        }
        sendHistoryPageToGlasses(
            page ?: GlassesChatHistoryPage.Page(emptyList(), hasMore = false),
            isLoadMore = true,
            reason = "history beginning reached",
        )
    }

    private fun sendHistoryPageToGlasses(
        page: GlassesChatHistoryPage.Page,
        isLoadMore: Boolean,
        reason: String,
    ) {
        val packets = GlassesChatHistoryPage.buildPackets(
            page.messages,
            CxrPayloadLimits.MAX_BYTES - CxrOutboundTransport.ACK_METADATA_RESERVE_BYTES,
            isLoadMore = isLoadMore,
            hasMore = page.hasMore,
        )
        Log.i(
            TAG,
            "Sending history ($reason): ${page.messages.size} messages, " +
                "${packets.size} packets, loadMore=$isLoadMore, hasMore=${page.hasMore}",
        )
        packets.forEach(glassesManager::sendRawMessage)
    }

    private fun sendHudCard(card: HudCard) {
        while (hudCardBodies.size >= MAX_HUD_CARDS) {
            hudCardBodies.keys.firstOrNull()?.let(hudCardBodies::remove) ?: break
        }
        hudCardBodies[card.id] = "${card.title}: ${card.body}"
        glassesManager.sendRawMessage(card.toJson(), isNewMessage = true)
    }

    private fun sendTtsState() {
        glassesManager.sendRawMessage(
            TtsState(
                enabled = ttsSettingsManager.isEnabled.value,
                voiceName = ttsSettingsManager.selectedVoiceName.value,
                provider = ttsSettingsManager.provider.value.name.lowercase(),
                playbackState = ttsPlaybackManager.state.value.name.lowercase(),
                canReplay = ttsPlaybackManager.canReplay.value,
            ).toJson(),
        )
    }

    private fun sendPhotoError(reason: String? = null) {
        glassesManager.sendRawMessage(
            PhotoResult(status = "error", reason = reason).toJson(),
        )
    }

    private fun beginPhotoCapture(
        attemptId: Long,
        sendAfterCapture: Boolean,
        visionPrompt: String?,
    ) {
        rokidDevice.onPhotoResult = { status, photoBytes ->
            scope.launch { completePhotoCapture(attemptId, status, photoBytes, sendAfterCapture, visionPrompt) }
        }
        photoCaptureTimeoutJob?.cancel()
        photoCaptureTimeoutJob = scope.launch {
            delay(PHOTO_CAPTURE_TIMEOUT_MS)
            if (photoCaptureGate.complete(attemptId)) {
                rokidDevice.onPhotoResult = null
                Log.e(TAG, "Photo capture timed out (attempt=$attemptId)")
                sendPhotoError("timeout")
            }
        }
        val status = rokidDevice.takePhoto(1280, 720, 80)
        if (status != ValueUtil.CxrStatus.REQUEST_SUCCEED && photoCaptureGate.complete(attemptId)) {
            photoCaptureTimeoutJob?.cancel()
            rokidDevice.onPhotoResult = null
            Log.e(TAG, "Photo capture request failed (attempt=$attemptId, status=$status)")
            sendPhotoError("request_failed")
        }
    }

    private suspend fun completePhotoCapture(
        attemptId: Long,
        status: ValueUtil.CxrStatus?,
        photoBytes: ByteArray?,
        sendAfterCapture: Boolean,
        visionPrompt: String?,
    ) {
        if (!photoCaptureGate.complete(attemptId)) {
            Log.w(TAG, "Ignoring stale photo callback (attempt=$attemptId)")
            return
        }
        photoCaptureTimeoutJob?.cancel()
        rokidDevice.onPhotoResult = null
        if (photoBytes == null || photoBytes.isEmpty()) {
            Log.e(TAG, "Photo capture failed (attempt=$attemptId, status=$status)")
            sendPhotoError("empty")
            return
        }
        if (prefs.getBoolean(KEY_SAVE_PHOTOS, false)) {
            scope.launch(Dispatchers.IO) { MediaStoreSaver.saveImage(appContext, photoBytes) }
        }
        if (sendAfterCapture) {
            val base64 = withContext(Dispatchers.Default) {
                Base64.encodeToString(photoBytes, Base64.NO_WRAP)
            }
            openClawClient.sendMessage(visionPrompt.orEmpty(), listOf(base64))
            return
        }
        val stored = pendingPhotoRepository.add(photoBytes)
        if (stored == null) {
            sendPhotoError("queue_limit")
            return
        }
        val thumbnail = withContext(Dispatchers.Default) {
            ImagePipeline.createHudThumbnail(photoBytes)
        }
        glassesManager.sendRawMessage(
            PhotoResult(
                status = "captured",
                thumbnail = thumbnail?.encoded,
                thumbnailFormat = thumbnail?.format,
                thumbnailWidth = thumbnail?.width,
                thumbnailHeight = thumbnail?.height,
            ).toJson(),
        )
    }

    private fun isGlassesConnected(): Boolean =
        glassesManager.connectionState.value is GlassesConnectionManager.ConnectionState.Connected

    private fun clearCallbacks() {
        openClawClient.onChatMessage = null
        openClawClient.onChatHistory = null
        openClawClient.onAgentThinking = null
        openClawClient.onAgentProgress = null
        openClawClient.onChatStream = null
        openClawClient.onChatStreamEnd = null
        openClawClient.onSessionList = null
        openClawClient.onSessionOperation = null
        openClawClient.onAgentList = null
        openClawClient.onConnectionUpdate = null
        openClawClient.onMoreHistoryLoaded = null
        glassesManager.onAiKeyDown = null
        glassesManager.onAiExit = null
        glassesManager.onMessageFromGlasses = null
    }

    private fun buildGlassesChatMessageJson(message: ChatMessage): String = HudChatMessage(
        id = message.id,
        role = message.role,
        content = message.content.take(2_000),
        timestamp = message.timestamp,
        attachments = message.attachments.take(4).mapNotNull { attachment ->
            val cacheIdentity = chatAttachmentFileStore.thumbnailCacheIdentity(attachment)
                ?: return@mapNotNull null
            val thumbnail = ImagePipeline.createHudThumbnail(cacheIdentity) {
                chatAttachmentFileStore.readBytes(attachment)
            } ?: return@mapNotNull null
            HudThumbnailAttachment(
                fileName = attachment.fileName ?: "photo",
                thumbnail = thumbnail.encoded,
                thumbnailFormat = thumbnail.format,
                thumbnailWidth = thumbnail.width,
                thumbnailHeight = thumbnail.height,
            )
        },
    ).toJson()

    private fun LiveCaptionState.toProtocol() = LiveCaptionUpdate(
        enabled = enabled,
        sourceText = sourceText,
        translatedText = translatedText,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        error = error,
    )

    private fun OpenClawClient.RunState.toProtocol(error: String?) = RunStateUpdate(
        state = name.lowercase(),
        canAbort = this !in setOf(
            OpenClawClient.RunState.IDLE,
            OpenClawClient.RunState.ERROR,
            OpenClawClient.RunState.ABORTING,
        ),
        error = error,
    )

    companion object {
        private const val TAG = "PhoneGlassesBridge"
        private const val PREFS_NAME = "clawsses"
        private const val KEY_SAVE_PHOTOS = "save_photos_to_gallery"
        private const val KEY_TRANSLATE_CAPTIONS = "translate_captions"
        private const val KEY_CAPTION_TARGET_LANGUAGE = "caption_target_language"
        private const val MAX_HUD_CARDS = 20
        private const val PHOTO_CAPTURE_TIMEOUT_MS = 20_000L
    }
}
