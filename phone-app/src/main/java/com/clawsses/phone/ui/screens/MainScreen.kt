package com.clawsses.phone.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawsses.phone.glasses.ApkInstaller
import com.clawsses.phone.glasses.GlassesConnectionManager
import com.clawsses.phone.glasses.RokidSdkManager
import com.clawsses.phone.glasses.WakeSignalManager
import com.clawsses.phone.media.MediaStoreSaver
import com.clawsses.phone.notifications.NotificationRelay
import com.clawsses.phone.openclaw.DeviceIdentity
import com.clawsses.phone.openclaw.OpenClawClient
import com.clawsses.phone.openclaw.GlassesChatHistoryPage
import com.clawsses.phone.talk.TalkModeManager
import com.clawsses.phone.talk.TalkModePhase
import com.clawsses.phone.talk.TalkModeSource
import com.clawsses.phone.ui.settings.SettingsScreen
import com.clawsses.phone.util.SecurePreferences
import com.clawsses.phone.tts.ElevenLabsClient
import com.clawsses.phone.tts.OpenAiTtsClient
import com.clawsses.phone.tts.TtsPlaybackManager
import com.clawsses.phone.tts.TtsSettingsManager
import com.clawsses.phone.tts.blocksVoiceCapture
import com.clawsses.phone.voice.VoiceCommandHandler
import com.clawsses.phone.voice.VoiceLanguageManager
import com.clawsses.phone.voice.LiveCaptionManager
import com.clawsses.phone.voice.VoiceRecognitionManager
import com.clawsses.shared.AgentInfo
import com.clawsses.shared.AgentProgressUpdate
import com.clawsses.shared.ChatMessage
import com.clawsses.shared.ConnectionUpdate
import com.clawsses.shared.CxrPayloadLimits
import com.clawsses.shared.HudCard
import com.clawsses.shared.HudCardAction
import com.clawsses.shared.LiveCaptionUpdate
import com.clawsses.shared.ModelInfo
import com.clawsses.shared.RunStateUpdate
import com.clawsses.shared.ScrollSettings
import com.clawsses.shared.ScrollSettingsUpdate
import com.clawsses.shared.TalkModeStateUpdate
import com.clawsses.shared.SessionInfo
import com.clawsses.shared.SessionOperationUpdate
import com.clawsses.shared.TtsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Managers
    val glassesManager = remember { GlassesConnectionManager(context) }
    val deviceIdentity = remember { DeviceIdentity(context) }
    val openClawClient = remember { OpenClawClient(deviceIdentity) }
    val voiceHandler = remember { VoiceCommandHandler(context) }
    val voiceLanguageManager = remember { VoiceLanguageManager(context) }
    val voiceRecognitionManager = remember { VoiceRecognitionManager(context) }
    val liveCaptionManager = remember { LiveCaptionManager(context) }
    val talkModeManager = remember { TalkModeManager(context) }
    val apkInstaller = remember { ApkInstaller(context) }
    val ttsSettingsManager = remember { TtsSettingsManager(context) }
    val elevenLabsClient = remember { ElevenLabsClient() }
    val openAiTtsClient = remember { OpenAiTtsClient() }
    val ttsPlaybackManager = remember {
        TtsPlaybackManager(context, elevenLabsClient, openAiTtsClient, ttsSettingsManager)
    }

    // State
    val glassesState by glassesManager.connectionState.collectAsState()
    val openClawState by openClawClient.connectionState.collectAsState()
    val runState by openClawClient.runState.collectAsState()
    val runError by openClawClient.runError.collectAsState()
    val talkModeState by talkModeManager.state.collectAsState()
    val chatMessages by openClawClient.chatMessages.collectAsState()
    val isListening by voiceRecognitionManager.isListening.collectAsState()
    val voiceMode by voiceRecognitionManager.activeMode.collectAsState()
    val installState by apkInstaller.installState.collectAsState()
    val selectedVoiceLanguage by voiceLanguageManager.selectedLanguage.collectAsState()
    val sessionList by openClawClient.sessionList.collectAsState()
    val agentList by openClawClient.agentList.collectAsState()
    val modelList by openClawClient.modelList.collectAsState()
    val currentModelRef by openClawClient.currentModelRef.collectAsState()
    val isSelectingModel by openClawClient.isSelectingModel.collectAsState()
    val modelSelectionError by openClawClient.modelSelectionError.collectAsState()
    val currentSessionKey by openClawClient.currentSessionKey.collectAsState()
    val unreadSessions by openClawClient.unreadSessions.collectAsState()
    val wakeOnStreamEnabled by glassesManager.wakeSignalManager.enabled.collectAsState()
    val ttsEnabled by ttsSettingsManager.isEnabled.collectAsState()
    val ttsVoiceName by ttsSettingsManager.selectedVoiceName.collectAsState()
    val ttsProvider by ttsSettingsManager.provider.collectAsState()
    val ttsPlaybackState by ttsPlaybackManager.state.collectAsState()
    val ttsCanReplay by ttsPlaybackManager.canReplay.collectAsState()
    val liveCaptionState by liveCaptionManager.state.collectAsState()

    // Persist OpenClaw settings in Android Keystore-backed encrypted preferences.
    val prefs = remember { SecurePreferences.create(context, "clawsses") }
    var openClawHost by remember {
        mutableStateOf(prefs.getString("openclaw_host", "10.0.2.2") ?: "10.0.2.2")
    }
    var openClawPort by remember {
        mutableStateOf(prefs.getString("openclaw_port", "18789") ?: "18789")
    }
    var openClawToken by remember {
        mutableStateOf(prefs.getString("openclaw_token", "") ?: "")
    }
    var savePhotosToGallery by remember {
        mutableStateOf(prefs.getBoolean("save_photos_to_gallery", false))
    }
    var scrollMessagesPerStep by remember {
        mutableIntStateOf(
            ScrollSettings.normalizeMessagesPerStep(
                prefs.getInt("scroll_messages_per_step", ScrollSettings.DEFAULT_MESSAGES_PER_STEP)
            )
        )
    }
    var translateCaptions by remember {
        mutableStateOf(prefs.getBoolean("translate_captions", false))
    }
    var captionTargetLanguage by remember {
        mutableStateOf(prefs.getString("caption_target_language", "English") ?: "English")
    }
    val hudCardBodies = remember { mutableStateMapOf<String, String>() }
    val phoneLoadingMore by openClawClient.isLoadingMoreHistory.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showSessionPicker by remember { mutableStateOf(false) }
    var showAgentPicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var pendingPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var pendingTalkRestart by remember { mutableStateOf<Runnable?>(null) }
    val latestTalkModeState = rememberUpdatedState(talkModeState)
    val latestOpenClawState = rememberUpdatedState(openClawState)
    val latestGlassesState = rememberUpdatedState(glassesState)
    val latestPendingPhotos = rememberUpdatedState(pendingPhotos)

    fun syncTalkModeStateToGlasses() {
        if (latestGlassesState.value is GlassesConnectionManager.ConnectionState.Connected) {
            val state = talkModeManager.state.value
            glassesManager.sendRawMessage(
                TalkModeStateUpdate(
                    enabled = state.enabled,
                    phase = state.phase.name.lowercase(),
                    interruptible = state.interruptible,
                    error = state.error,
                ).toJson()
            )
        }
    }

    fun syncScrollSettingsToGlasses(messagesPerStep: Int = scrollMessagesPerStep) {
        if (latestGlassesState.value is GlassesConnectionManager.ConnectionState.Connected) {
            glassesManager.sendRawMessage(
                ScrollSettingsUpdate(
                    messagesPerStep = ScrollSettings.normalizeMessagesPerStep(messagesPerStep)
                ).toJson()
            )
        }
    }

    fun sendHistoryToGlasses(messages: List<ChatMessage>, reason: String) {
        val packets = GlassesChatHistoryPage.buildPackets(messages)
        android.util.Log.i(
            "MainScreen",
            "Sending complete chunked history ($reason): ${messages.size} source messages, " +
                "${packets.size} CXR packets, max=${packets.maxOf { CxrPayloadLimits.byteSize(it) }} bytes"
        )
        packets.forEach(glassesManager::sendRawMessage)
    }

    fun sendHudCard(card: HudCard) {
        if (hudCardBodies.size >= 20) hudCardBodies.keys.firstOrNull()?.let(hudCardBodies::remove)
        hudCardBodies[card.id] = "${card.title}: ${card.body}"
        glassesManager.sendRawMessage(card.toJson(), isNewMessage = true)
    }

    fun sendTalkVoiceState(state: String, mode: String? = null, text: String? = null) {
        if (latestGlassesState.value !is GlassesConnectionManager.ConnectionState.Connected) return
        glassesManager.sendRawMessage(
            org.json.JSONObject().apply {
                put("type", "voice_state")
                put("state", state)
                if (mode != null) put("mode", mode)
                if (text != null) put("text", text)
            }.toString()
        )
    }

    fun stopTalkMode(disable: Boolean) {
        pendingTalkRestart?.let(mainHandler::removeCallbacks)
        pendingTalkRestart = null
        voiceRecognitionManager.stopListening()
        voiceRecognitionManager.onSpeechStopped = null
        ttsPlaybackManager.stop()
        openClawClient.abortActiveRun()
        RokidSdkManager.clearCommunicationDevice()
        if (disable) talkModeManager.setEnabled(false) else talkModeManager.resetToIdle()
        sendTalkVoiceState("idle")
        syncTalkModeStateToGlasses()
    }

    fun setLiveCaptionsEnabled(enabled: Boolean) {
        if (enabled) {
            if (talkModeManager.state.value.enabled) stopTalkMode(disable = true)
            voiceRecognitionManager.stopListening()
            RokidSdkManager.setCommunicationDevice()
            liveCaptionManager.start(
                sourceLanguage = voiceLanguageManager.getActiveLanguageTag(),
                targetLanguage = captionTargetLanguage,
                translate = translateCaptions,
            )
        } else {
            liveCaptionManager.stop()
            RokidSdkManager.clearCommunicationDevice()
        }
    }

    fun switchToHiRokid() {
        if (talkModeManager.state.value.enabled) stopTalkMode(disable = true)
        setLiveCaptionsEnabled(false)
        voiceRecognitionManager.stopListening()
        ttsPlaybackManager.stop()
        glassesManager.disconnect()
        com.clawsses.phone.service.GlassesConnectionService.stop(context)
        mainHandler.postDelayed({
            val packageName = "com.rokid.sprite.global.aiapp"
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            val launched = launchIntent != null && runCatching {
                context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (!launched) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Toast.makeText(context, "Enable or install Hi Rokid, then open it", Toast.LENGTH_LONG).show()
            }
        }, 900L)
    }

    lateinit var scheduleTalkRestart: (Long) -> Unit
    val prepareTtsPlayback = {
        pendingTalkRestart?.let(mainHandler::removeCallbacks)
        pendingTalkRestart = null
        voiceRecognitionManager.stopListening()
        RokidSdkManager.clearCommunicationDevice()
    }
    val startTalkListening: (TalkModeSource, Boolean) -> Unit = startTalk@{ source, interruptCurrent ->
        if (TtsPlaybackManager.isPlaybackActive()) {
            android.util.Log.i("MainScreen", "Ignoring voice start while process-wide TTS is active")
            return@startTalk
        }
        pendingTalkRestart?.let(mainHandler::removeCallbacks)
        pendingTalkRestart = null
        val current = talkModeManager.state.value
        if (!current.enabled) return@startTalk

        if (latestOpenClawState.value !is OpenClawClient.ConnectionState.Connected ||
            (source == TalkModeSource.GLASSES &&
                latestGlassesState.value !is GlassesConnectionManager.ConnectionState.Connected)
        ) {
            talkModeManager.setPhase(TalkModePhase.DISCONNECTED)
            syncTalkModeStateToGlasses()
            return@startTalk
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
            sendTalkVoiceState("listening", mode)
        }

        voiceRecognitionManager.onSpeechStopped = {
            val latest = talkModeManager.state.value
            if (latest.enabled && latest.cycleId == cycleId) {
                talkModeManager.setPhase(TalkModePhase.TRANSCRIBING, cycleId)
                if (source == TalkModeSource.GLASSES) sendTalkVoiceState("processing", mode)
            }
        }

        voiceRecognitionManager.startListening(
            languageTag = voiceLanguageManager.getActiveLanguageTag()
        ) { result ->
            val latest = talkModeManager.state.value
            if (!latest.enabled || latest.cycleId != cycleId) return@startListening
            if (source == TalkModeSource.GLASSES) RokidSdkManager.clearCommunicationDevice()

            when (result) {
                is VoiceCommandHandler.VoiceResult.Text -> {
                    val text = result.text.trim()
                    if (text.isEmpty()) {
                        if (source == TalkModeSource.GLASSES) {
                            RokidSdkManager.notifyAsrNone()
                            sendTalkVoiceState("idle")
                        }
                        talkModeManager.resetToIdle()
                        scheduleTalkRestart(800L)
                        return@startListening
                    }

                    if (source == TalkModeSource.GLASSES) {
                        RokidSdkManager.sendAsrContent(text)
                        RokidSdkManager.notifyAsrEnd()
                        glassesManager.sendRawMessage(
                            org.json.JSONObject().apply {
                                put("type", "voice_result")
                                put("result_type", "text")
                                put("text", text)
                                put("autoSent", true)
                            }.toString()
                        )
                        mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 500)
                    }

                    talkModeManager.setPhase(TalkModePhase.SENDING, cycleId)
                    scope.launch {
                        val runBusy = openClawClient.runState.value !in setOf(
                            OpenClawClient.RunState.IDLE,
                            OpenClawClient.RunState.ERROR,
                        )
                        if (runBusy) openClawClient.abortActiveRun()
                        val ready = withTimeoutOrNull(12_000) {
                            openClawClient.runState.first {
                                it == OpenClawClient.RunState.IDLE || it == OpenClawClient.RunState.ERROR
                            }
                        } != null
                        val stateBeforeSend = talkModeManager.state.value
                        if (!stateBeforeSend.enabled || stateBeforeSend.cycleId != cycleId) return@launch
                        if (!ready) {
                            talkModeManager.setPhase(
                                TalkModePhase.ERROR,
                                cycleId,
                                "Previous run did not stop",
                            )
                            scheduleTalkRestart(1_500L)
                            return@launch
                        }

                        val images = latestPendingPhotos.value.ifEmpty { null }
                        talkModeManager.setPhase(TalkModePhase.WAITING, cycleId)
                        openClawClient.sendMessage(text, images)
                        if (images != null) {
                            pendingPhotos = emptyList()
                            glassesManager.sendRawMessage("""{"type":"remove_photo","all":true}""")
                        }
                    }
                }
                is VoiceCommandHandler.VoiceResult.Command -> {
                    val command = result.command.lowercase()
                    if (command in setOf(
                            "stop talk mode",
                            "talk mode off",
                            "talk modus stoppen",
                            "talk modus aus",
                        )
                    ) {
                        stopTalkMode(disable = true)
                    } else {
                        if (latestGlassesState.value is GlassesConnectionManager.ConnectionState.Connected) {
                            glassesManager.sendRawMessage(
                                org.json.JSONObject().apply {
                                    put("type", "voice_result")
                                    put("result_type", "command")
                                    put("text", result.command)
                                    put("autoSent", true)
                                }.toString()
                            )
                        }
                        talkModeManager.resetToIdle()
                        scheduleTalkRestart(600L)
                    }
                }
                is VoiceCommandHandler.VoiceResult.Error -> {
                    if (source == TalkModeSource.GLASSES) {
                        RokidSdkManager.notifyAsrError()
                        sendTalkVoiceState("error")
                    }
                    talkModeManager.setPhase(TalkModePhase.ERROR, cycleId, result.message)
                    scheduleTalkRestart(1_500L)
                }
            }
            syncTalkModeStateToGlasses()
        }
    }

    scheduleTalkRestart = { delayMs ->
        pendingTalkRestart?.let(mainHandler::removeCallbacks)
        val restart = Runnable {
            val state = talkModeManager.state.value
            pendingTalkRestart = null
            if (state.enabled &&
                !TtsPlaybackManager.isPlaybackActive() &&
                !ttsPlaybackManager.state.value.blocksVoiceCapture()
            ) {
                startTalkListening(state.source, false)
            }
        }
        pendingTalkRestart = restart
        mainHandler.postDelayed(restart, delayMs)
    }

    // Initialize voice handler and query available languages
    LaunchedEffect(Unit) {
        voiceHandler.initialize()
        voiceLanguageManager.queryAvailableLanguages()
        // Set up partial result forwarding for both voice recognition managers
        voiceHandler.onPartialResult = { partialText ->
            RokidSdkManager.sendAsrContent(partialText)
            val stateMsg = org.json.JSONObject().apply {
                put("type", "voice_state")
                put("state", "recognizing")
                put("text", partialText)
            }
            glassesManager.sendRawMessage(stateMsg.toString())
        }
        voiceRecognitionManager.onPartialResult = { partialText ->
            RokidSdkManager.sendAsrContent(partialText)
            val stateMsg = org.json.JSONObject().apply {
                put("type", "voice_state")
                put("state", "recognizing")
                put("text", partialText)
            }
            glassesManager.sendRawMessage(stateMsg.toString())
        }

        // Try to auto-reconnect to previously paired glasses on startup
        glassesManager.tryAutoReconnectOnStartup()

        // Restore the saved private OpenClaw connection after process/activity restart.
        // Without this, the HUD can reconnect to the glasses while chat remains offline.
        if (openClawHost.isNotBlank()) {
            val portNum = openClawPort.toIntOrNull() ?: 18789
            openClawClient.connect(openClawHost, portNum, openClawToken)
        }
    }

    // Fetch session list when OpenClaw connects
    LaunchedEffect(openClawState) {
        if (openClawState is OpenClawClient.ConnectionState.Connected) {
            openClawClient.requestSessions()
            openClawClient.requestAgents()
            openClawClient.requestModels()
        } else if (talkModeManager.state.value.enabled) {
            talkModeManager.setPhase(TalkModePhase.DISCONNECTED)
        }
    }

    LaunchedEffect(talkModeState, glassesState) {
        syncTalkModeStateToGlasses()
    }

    LaunchedEffect(liveCaptionState, glassesState) {
        if (glassesState is GlassesConnectionManager.ConnectionState.Connected) {
            glassesManager.sendRawMessage(
                LiveCaptionUpdate(
                    enabled = liveCaptionState.enabled,
                    sourceText = liveCaptionState.sourceText,
                    translatedText = liveCaptionState.translatedText,
                    sourceLanguage = liveCaptionState.sourceLanguage,
                    targetLanguage = liveCaptionState.targetLanguage,
                    error = liveCaptionState.error,
                ).toJson()
            )
        }
    }

    LaunchedEffect(glassesState) {
        if (glassesState !is GlassesConnectionManager.ConnectionState.Connected) return@LaunchedEffect
        NotificationRelay.pending.collect { pending ->
            pending.forEach { notification ->
                val card = HudCard(
                    id = notification.id,
                    source = notification.appName,
                    title = notification.title,
                    body = notification.body.take(420),
                    expiresAt = System.currentTimeMillis() + 90_000L,
                    actions = listOf(
                        HudCardAction("summarize", "Summarize"),
                        HudCardAction("dismiss", "Dismiss"),
                    ),
                )
                sendHudCard(card)
                NotificationRelay.consume(notification.id)
            }
        }
    }

    // Resume the durable mode only when its selected audio endpoint and OpenClaw are ready.
    LaunchedEffect(openClawState, glassesState, talkModeState.enabled, talkModeState.phase) {
        val sourceReady = talkModeState.source != TalkModeSource.GLASSES ||
            glassesState is GlassesConnectionManager.ConnectionState.Connected
        if (talkModeState.enabled &&
            talkModeState.phase == TalkModePhase.DISCONNECTED &&
            openClawState is OpenClawClient.ConnectionState.Connected && sourceReady
        ) {
            startTalkListening(talkModeState.source, false)
        }
    }

    // Sync TTS state to glasses when settings change
    LaunchedEffect(ttsEnabled, ttsVoiceName, ttsProvider, ttsPlaybackState, ttsCanReplay) {
        if (glassesState is GlassesConnectionManager.ConnectionState.Connected) {
            val ttsStateMsg = TtsState(
                enabled = ttsEnabled,
                voiceName = ttsVoiceName,
                provider = ttsProvider.name.lowercase(),
                playbackState = ttsPlaybackState.name.lowercase(),
                canReplay = ttsCanReplay,
            )
            glassesManager.sendRawMessage(ttsStateMsg.toJson())
        }
    }

    LaunchedEffect(runState, runError, glassesState) {
        if (glassesState is GlassesConnectionManager.ConnectionState.Connected) {
            glassesManager.sendRawMessage(
                RunStateUpdate(
                    state = runState.name.lowercase(),
                    canAbort = runState !in setOf(
                        OpenClawClient.RunState.IDLE,
                        OpenClawClient.RunState.ERROR,
                        OpenClawClient.RunState.ABORTING
                    ),
                    error = runError
                ).toJson()
            )
        }

        val talk = talkModeManager.state.value
        if (talk.enabled) {
            when (runState) {
                OpenClawClient.RunState.WAITING,
                OpenClawClient.RunState.REASONING,
                OpenClawClient.RunState.STREAMING -> {
                    if (talk.phase !in setOf(
                            TalkModePhase.LISTENING,
                            TalkModePhase.TRANSCRIBING,
                            TalkModePhase.SENDING,
                        )
                    ) talkModeManager.setPhase(TalkModePhase.WAITING)
                }
                OpenClawClient.RunState.ABORTING -> {
                    if (talk.phase != TalkModePhase.LISTENING) {
                        talkModeManager.setPhase(TalkModePhase.ABORTING)
                    }
                }
                OpenClawClient.RunState.ERROR -> {
                    if (talk.phase == TalkModePhase.WAITING) {
                        talkModeManager.setPhase(TalkModePhase.ERROR, error = runError)
                        scheduleTalkRestart(1_500L)
                    }
                }
                else -> {}
            }
        }
    }

    LaunchedEffect(ttsPlaybackState) {
        val talk = talkModeManager.state.value
        if (!talk.enabled) return@LaunchedEffect
        when (ttsPlaybackState) {
            com.clawsses.phone.tts.TtsPlaybackState.SYNTHESIZING,
            com.clawsses.phone.tts.TtsPlaybackState.PLAYING -> {
                prepareTtsPlayback()
                if (talk.enabled) talkModeManager.setPhase(TalkModePhase.SPEAKING)
            }
            com.clawsses.phone.tts.TtsPlaybackState.IDLE -> {
                if (talk.phase == TalkModePhase.SPEAKING) {
                    talkModeManager.resetToIdle()
                    scheduleTalkRestart(450L)
                }
            }
            com.clawsses.phone.tts.TtsPlaybackState.ERROR -> {
                if (talk.phase == TalkModePhase.SPEAKING) {
                    talkModeManager.setPhase(TalkModePhase.ERROR, error = "Voice playback failed")
                    scheduleTalkRestart(1_500L)
                }
            }
        }
    }

    // Start/stop foreground service based on glasses connection state,
    // and send current chat history when glasses connect.
    // IMPORTANT: Don't stop the service during Reconnecting — killing the foreground
    // service drops the wake lock and lets Android kill the Bluetooth connection,
    // making reconnection impossible. Only stop on true Disconnected (not reconnecting).
    LaunchedEffect(glassesState) {
        when (glassesState) {
            is GlassesConnectionManager.ConnectionState.Connected -> {
                android.util.Log.i("MainScreen", "Glasses connected — starting foreground service")
                com.clawsses.phone.service.GlassesConnectionService.start(context)
                // Send current chat history to glasses if we have any
                val currentMessages = openClawClient.chatMessages.value
                if (currentMessages.isNotEmpty()) {
                    sendHistoryToGlasses(currentMessages, "glasses connected")
                }
                // Send TTS state to glasses
                val ttsStateMsg = TtsState(
                    enabled = ttsSettingsManager.isEnabled.value,
                    voiceName = ttsSettingsManager.selectedVoiceName.value,
                    provider = ttsSettingsManager.provider.value.name.lowercase(),
                    playbackState = ttsPlaybackManager.state.value.name.lowercase(),
                    canReplay = ttsPlaybackManager.canReplay.value,
                )
                glassesManager.sendRawMessage(ttsStateMsg.toJson())
                glassesManager.sendRawMessage(
                    RunStateUpdate(
                        state = openClawClient.runState.value.name.lowercase(),
                        canAbort = openClawClient.runState.value !in setOf(
                            OpenClawClient.RunState.IDLE,
                            OpenClawClient.RunState.ERROR,
                            OpenClawClient.RunState.ABORTING
                        ),
                        error = openClawClient.runError.value
                    ).toJson()
                )
                syncScrollSettingsToGlasses()
            }
            is GlassesConnectionManager.ConnectionState.Disconnected -> {
                val talk = talkModeManager.state.value
                if (talk.enabled && talk.source == TalkModeSource.GLASSES) {
                    pendingTalkRestart?.let(mainHandler::removeCallbacks)
                    pendingTalkRestart = null
                    voiceRecognitionManager.stopListening()
                    RokidSdkManager.clearCommunicationDevice()
                    talkModeManager.setPhase(TalkModePhase.DISCONNECTED)
                }
                // Only stop the service if we're truly disconnected (no saved pairing to reconnect to).
                // If we have a pairing, the service keeps BT alive for auto-reconnect.
                if (!RokidSdkManager.hasSavedConnectionInfo()) {
                    android.util.Log.i("MainScreen", "Glasses disconnected (no pairing) — stopping foreground service")
                    com.clawsses.phone.service.GlassesConnectionService.stop(context)
                } else {
                    android.util.Log.i("MainScreen", "Glasses disconnected but paired — keeping foreground service for reconnect")
                }
            }
            is GlassesConnectionManager.ConnectionState.Reconnecting -> {
                // Keep foreground service alive during reconnection attempts
                android.util.Log.i("MainScreen", "Glasses reconnecting — keeping foreground service")
                com.clawsses.phone.service.GlassesConnectionService.start(context)
            }
            else -> {}
        }
    }

    // Auto-scroll to bottom when new messages arrive (but not during load-more).
    // Detect prepend by checking if the first message ID changed — this is more
    // reliable than checking phoneLoadingMore which may already be false by the
    // time this effect runs (both StateFlows update in the same coroutine frame).
    var previousFirstMsgId by remember { mutableStateOf<String?>(null) }
    var followPhoneTail by remember { mutableStateOf(true) }
    val phoneListDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(phoneListDragged, listState.canScrollForward) {
        val isAtBottom = !listState.canScrollForward
        if (phoneListDragged || isAtBottom) followPhoneTail = isAtBottom
    }
    val phoneTailVersion = chatMessages.lastOrNull()?.let { "${it.id}:${it.content.length}" }
    LaunchedEffect(chatMessages.size, phoneTailVersion) {
        if (chatMessages.isNotEmpty()) {
            val currentFirstId = chatMessages.first().id
            val wasPrepend = previousFirstMsgId != null && currentFirstId != previousFirstMsgId
            if (!wasPrepend && followPhoneTail) {
                listState.animateScrollToItem(chatMessages.size - 1)
                yield()
                listState.animateScrollBy(Float.MAX_VALUE)
            }
            previousFirstMsgId = currentFirstId
        }
    }

    // Phone UI: detect scroll-to-top and load more history
    val phoneCanScrollBackward by remember { derivedStateOf { listState.canScrollBackward } }
    LaunchedEffect(phoneCanScrollBackward) {
        if (!phoneCanScrollBackward && chatMessages.isNotEmpty() && !phoneLoadingMore) {
            openClawClient.loadMoreHistory()
        }
    }

    // Wire OpenClaw client callbacks to forward to glasses
    LaunchedEffect(Unit) {
        openClawClient.onChatMessage = { msg ->
            // Check if this is a spontaneous message (not preceded by our stream start)
            // This could be a cron job message or a message from another session
            val isNewMessage = msg.role == "assistant" && !glassesManager.wakeSignalManager.wakeState.value.let {
                it is WakeSignalManager.WakeState.Awake || it is WakeSignalManager.WakeState.WakingUp
            }
            glassesManager.sendRawMessage(buildGlassesChatMessageJson(msg), isNewMessage = isNewMessage)
            if (isNewMessage && msg.content.isNotBlank()) {
                sendHudCard(
                    HudCard(
                        id = "openclaw:${msg.id}",
                        source = "OpenClaw",
                        title = "New assistant update",
                        body = msg.content.take(420),
                        priority = "high",
                        expiresAt = System.currentTimeMillis() + 120_000L,
                        actions = listOf(
                            HudCardAction("reply", "Reply"),
                            HudCardAction("dismiss", "Dismiss"),
                        ),
                    )
                )
            }
        }
        openClawClient.onChatHistory = { messages ->
            sendHistoryToGlasses(messages, "history callback")
        }
        openClawClient.onAgentThinking = { msg ->
            // Agent is about to start streaming — notify wake manager
            glassesManager.notifyStreamStart(msg.id)
            glassesManager.sendRawMessage(msg.toJson(), isStreamContent = true)
        }
        openClawClient.onAgentProgress = { update: AgentProgressUpdate ->
            glassesManager.sendRawMessage(update.toJson(), isStreamContent = true)
        }
        openClawClient.onChatStream = { msg ->
            // Streaming content — mark as such for wake signal handling
            glassesManager.sendRawMessage(msg.toJson(), isStreamContent = true)
        }
        openClawClient.onChatStreamEnd = { msg ->
            // Streaming complete — notify wake manager
            glassesManager.notifyStreamEnd(msg.id)
            glassesManager.sendRawMessage(msg.toJson())
            // Trigger TTS if enabled
            val fullText = openClawClient.chatMessages.value.lastOrNull { it.id == msg.id }?.content
            val talk = talkModeManager.state.value
            if (msg.state == "final" && fullText != null) {
                if (talk.enabled) {
                    if (ttsSettingsManager.isEnabled.value &&
                        ttsSettingsManager.isConfigured() && fullText.isNotBlank()
                    ) {
                        prepareTtsPlayback()
                        talkModeManager.setPhase(TalkModePhase.SPEAKING)
                        ttsPlaybackManager.onMessageComplete(fullText)
                    } else {
                        talkModeManager.resetToIdle()
                        scheduleTalkRestart(450L)
                    }
                } else {
                    if (ttsSettingsManager.isEnabled.value &&
                        ttsSettingsManager.isConfigured() && fullText.isNotBlank()
                    ) {
                        prepareTtsPlayback()
                    }
                    ttsPlaybackManager.onMessageComplete(fullText)
                }
            } else if (msg.state != "final") {
                ttsPlaybackManager.stop()
                if (talk.enabled && talk.phase !in setOf(
                        TalkModePhase.LISTENING,
                        TalkModePhase.TRANSCRIBING,
                        TalkModePhase.SENDING,
                    )
                ) {
                    talkModeManager.resetToIdle()
                    scheduleTalkRestart(800L)
                }
            }
        }
        openClawClient.onSessionList = { msg ->
            val payload = msg.toJson()
            if (CxrPayloadLimits.fits(payload)) {
                glassesManager.sendRawMessage(payload)
            } else {
                android.util.Log.e(
                    "MainScreen",
                    "Session page exceeds CXR limit (${CxrPayloadLimits.byteSize(payload)} bytes)"
                )
                glassesManager.sendRawMessage(
                    SessionOperationUpdate(
                        operation = "list",
                        state = "error",
                        error = "Session page is too large"
                    ).toJson()
                )
            }
        }
        openClawClient.onSessionOperation = { msg ->
            glassesManager.sendRawMessage(msg.toJson())
        }
        openClawClient.onAgentList = { msg ->
            glassesManager.sendRawMessage(msg.toJson())
        }
        openClawClient.onConnectionUpdate = { msg ->
            glassesManager.sendRawMessage(msg.toJson())
        }
        openClawClient.onMoreHistoryLoaded = { prependedCount, hasMore ->
            val allMessages = openClawClient.chatMessages.value
            sendHistoryToGlasses(
                allMessages,
                "phone history expansion; prepended=$prependedCount, phoneHasMore=$hasMore"
            )
        }
    }

    // Handle AI scene events (glasses long-press triggers voice input)
    fun capturePhoto(sendAfterCapture: Boolean, visionPrompt: String? = null) {
        RokidSdkManager.onPhotoResult = { status, photoBytes ->
            mainHandler.post {
                if (photoBytes != null && photoBytes.isNotEmpty()) {
                    if (savePhotosToGallery) {
                        scope.launch(Dispatchers.IO) {
                            MediaStoreSaver.saveImage(context, photoBytes)
                        }
                    }
                    val base64 = android.util.Base64.encodeToString(
                        photoBytes,
                        android.util.Base64.NO_WRAP
                    )
                    if (sendAfterCapture) {
                        openClawClient.sendMessage(visionPrompt.orEmpty(), listOf(base64))
                    } else {
                        pendingPhotos = pendingPhotos + base64
                        val resultMsg = org.json.JSONObject().apply {
                            put("type", "photo_result")
                            put("status", "captured")
                            put("thumbnail", createThumbnailBase64(photoBytes, 80, 60))
                        }
                        glassesManager.sendRawMessage(resultMsg.toString())
                    }
                } else {
                    android.util.Log.e("MainScreen", "Photo capture failed (status=$status)")
                    glassesManager.sendRawMessage(
                        org.json.JSONObject().apply {
                            put("type", "photo_result")
                            put("status", "error")
                        }.toString()
                    )
                }
                RokidSdkManager.onPhotoResult = null
            }
        }
        RokidSdkManager.takeGlassPhotoGlobal(1280, 720, 80)
    }

    LaunchedEffect(Unit) {
        glassesManager.onAiKeyDown = {
            android.util.Log.i("MainScreen", ">>> AI key down from glasses - starting voice recognition")
            mainHandler.post {
                if (TtsPlaybackManager.isPlaybackActive() ||
                    ttsPlaybackManager.state.value.blocksVoiceCapture()
                ) {
                    android.util.Log.i(
                        "MainScreen",
                        "Ignoring Rokid AI-key callback while TTS is active",
                    )
                    return@post
                }
                if (liveCaptionManager.state.value.enabled) setLiveCaptionsEnabled(false)
                val talk = talkModeManager.state.value
                if (talk.enabled) {
                    startTalkListening(
                        TalkModeSource.GLASSES,
                        talk.phase in setOf(
                            TalkModePhase.SENDING,
                            TalkModePhase.WAITING,
                            TalkModePhase.SPEAKING,
                            TalkModePhase.ABORTING,
                        ) || ttsPlaybackManager.state.value != com.clawsses.phone.tts.TtsPlaybackState.IDLE
                    )
                } else {
                    RokidSdkManager.setCommunicationDevice()
                    startVoiceRecognitionWithManager(
                        voiceRecognitionManager = voiceRecognitionManager,
                        voiceHandler = voiceHandler,
                        openClawClient = openClawClient,
                        glassesManager = glassesManager,
                        mainHandler = mainHandler,
                        isRetry = false,
                        languageTag = voiceLanguageManager.getActiveLanguageTag(),
                        pendingPhotos = { pendingPhotos },
                        onPhotosConsumed = { pendingPhotos = emptyList() }
                    )
                }
            }
        }
        glassesManager.onAiExit = {
            android.util.Log.d("MainScreen", "AI scene exited on glasses (recognizer continues)")
        }
    }

    // Handle messages from glasses and forward to OpenClaw
    LaunchedEffect(Unit) {
        glassesManager.onMessageFromGlasses = onGlassesMessage@{ message ->
            try {
                val json = org.json.JSONObject(message)
                val type = json.optString("type", "")
                when (type) {
                    "user_input" -> {
                        val text = json.optString("text", "")
                        val images = pendingPhotos.ifEmpty { null }
                        android.util.Log.d("MainScreen", "Received user input from glasses (${text.length} chars, photos=${pendingPhotos.size})")
                        if (text.isNotEmpty() || images != null) {
                            openClawClient.sendMessage(text, images)
                        }
                        pendingPhotos = emptyList()
                    }
                    "start_voice" -> {
                        android.util.Log.d("MainScreen", "Glasses requested voice recognition start")
                        if (TtsPlaybackManager.isPlaybackActive() ||
                            ttsPlaybackManager.state.value.blocksVoiceCapture()
                        ) {
                            android.util.Log.i(
                                "MainScreen",
                                "Ignoring automatic glasses voice restart while TTS is active",
                            )
                            return@onGlassesMessage
                        }
                        if (liveCaptionManager.state.value.enabled) setLiveCaptionsEnabled(false)
                        val talk = talkModeManager.state.value
                        if (talk.enabled) {
                            startTalkListening(
                                TalkModeSource.GLASSES,
                                talk.interruptible ||
                                    ttsPlaybackManager.state.value != com.clawsses.phone.tts.TtsPlaybackState.IDLE
                            )
                        } else {
                        com.clawsses.phone.glasses.RokidSdkManager.setCommunicationDevice()
                        // Keep SDK AI scene alive (it times out without ASR content)
                        com.clawsses.phone.glasses.RokidSdkManager.sendAsrContent("...")
                        // Send voice state with mode info
                        val modeIndicator = if (voiceRecognitionManager.isOpenAIAvailable()) "openai" else "device"
                        // Send "processing" state when VAD detects speech end
                        voiceRecognitionManager.onSpeechStopped = {
                            val processingMsg = org.json.JSONObject().apply {
                                put("type", "voice_state")
                                put("state", "processing")
                                put("mode", modeIndicator)
                            }
                            glassesManager.sendRawMessage(processingMsg.toString())
                        }
                        voiceRecognitionManager.startListening(languageTag = voiceLanguageManager.getActiveLanguageTag()) { result ->
                            com.clawsses.phone.glasses.RokidSdkManager.clearCommunicationDevice()
                            when (result) {
                                is VoiceCommandHandler.VoiceResult.Text -> {
                                    android.util.Log.d("MainScreen", "Voice result received (${result.text.length} chars)")
                                    val resultMsg = org.json.JSONObject().apply {
                                        put("type", "voice_result")
                                        put("result_type", "text")
                                        put("text", result.text)
                                    }
                                    glassesManager.sendRawMessage(resultMsg.toString())
                                    // Don't send to OpenClaw here — glasses stages the text
                                    // and sends user_input when user confirms via Send button
                                }
                                is VoiceCommandHandler.VoiceResult.Command -> {
                                    android.util.Log.d("MainScreen", "Voice result command: ${result.command}")
                                    val resultMsg = org.json.JSONObject().apply {
                                        put("type", "voice_result")
                                        put("result_type", "command")
                                        put("text", result.command)
                                    }
                                    glassesManager.sendRawMessage(resultMsg.toString())
                                }
                                is VoiceCommandHandler.VoiceResult.Error -> {
                                    android.util.Log.e("MainScreen", "Voice recognition failed")
                                    val resultMsg = org.json.JSONObject().apply {
                                        put("type", "voice_result")
                                        put("result_type", "error")
                                        put("text", result.message)
                                    }
                                    glassesManager.sendRawMessage(resultMsg.toString())
                                }
                            }
                        }
                        val stateMsg = org.json.JSONObject().apply {
                            put("type", "voice_state")
                            put("state", "listening")
                            put("mode", modeIndicator)
                        }
                        glassesManager.sendRawMessage(stateMsg.toString())
                        }
                    }
                    "cancel_voice" -> {
                        android.util.Log.d("MainScreen", "Glasses requested voice recognition cancel")
                        if (talkModeManager.state.value.enabled) {
                            stopTalkMode(disable = true)
                        } else {
                            voiceRecognitionManager.stopListening()
                            com.clawsses.phone.glasses.RokidSdkManager.clearCommunicationDevice()
                            val stateMsg = org.json.JSONObject().apply {
                                put("type", "voice_state")
                                put("state", "idle")
                            }
                            glassesManager.sendRawMessage(stateMsg.toString())
                        }
                    }
                    "list_sessions" -> {
                        android.util.Log.d("MainScreen", "Requesting session list for glasses")
                        openClawClient.requestSessionPage(json.optInt("offset", 0))
                    }
                    "switch_session" -> {
                        val sessionKey = json.optString("sessionKey", "")
                        android.util.Log.d("MainScreen", "Switching session")
                        if (sessionKey.isNotEmpty()) {
                            openClawClient.switchSession(sessionKey)
                        }
                    }
                    "create_session" -> {
                        android.util.Log.d("MainScreen", "Creating new session from glasses")
                        openClawClient.createSession()
                    }
                    "list_agents" -> {
                        android.util.Log.d("MainScreen", "Requesting agent list for glasses")
                        openClawClient.requestAgents()
                    }
                    "switch_agent" -> {
                        val agentId = json.optString("agentId", "")
                        val agentName = json.optString("agentName", "").takeIf { it.isNotBlank() }
                        if (agentId.isNotEmpty()) {
                            openClawClient.switchAgent(agentId, agentName)
                        }
                    }
                    "abort_run" -> {
                        ttsPlaybackManager.stop()
                        openClawClient.abortActiveRun()
                    }
                    "slash_command" -> {
                        val command = json.optString("command", "")
                        android.util.Log.d("MainScreen", "Slash command received from glasses")
                        if (command.isNotEmpty()) {
                            openClawClient.sendSlashCommand(command)
                        }
                    }
                    "request_state" -> {
                        val glassesVersionName = json.optString("versionName").takeIf { it.isNotBlank() }
                        val glassesVersionCode = json.optInt("versionCode", -1).takeIf { it >= 0 }
                        android.util.Log.d(
                            "MainScreen",
                            if (glassesVersionName != null && glassesVersionCode != null) {
                                "Glasses requested current state (app=$glassesVersionName, build=$glassesVersionCode)"
                            } else {
                                "Glasses requested current state (legacy build)"
                            }
                        )
                        // Send OpenClaw connection status
                        val isConnected = openClawState is OpenClawClient.ConnectionState.Connected
                        val currentKey = openClawClient.currentSessionKey.value
                        val currentName = currentKey?.let { key ->
                            val agentId = openClawClient.agentIdFromSessionKey(key)
                            openClawClient.agentList.value.firstOrNull { it.id == agentId }?.name
                                ?: openClawClient.sessionList.value.firstOrNull { it.key == key }?.name
                        }
                        val connUpdate = ConnectionUpdate(
                            connected = isConnected,
                            sessionId = currentKey,
                            sessionName = currentName
                        )
                        glassesManager.sendRawMessage(connUpdate.toJson())
                        glassesManager.sendRawMessage(
                            openClawClient.currentAgentListUpdate().toJson()
                        )
                        openClawClient.requestModels()
                        // Send current chat history
                        val currentMessages = openClawClient.chatMessages.value
                        sendHistoryToGlasses(currentMessages, "HUD state request")
                        // Send TTS state
                        val ttsStateMsg = TtsState(
                            enabled = ttsSettingsManager.isEnabled.value,
                            voiceName = ttsSettingsManager.selectedVoiceName.value,
                            provider = ttsSettingsManager.provider.value.name.lowercase(),
                            playbackState = ttsPlaybackManager.state.value.name.lowercase(),
                            canReplay = ttsPlaybackManager.canReplay.value,
                        )
                        glassesManager.sendRawMessage(ttsStateMsg.toJson())
                        glassesManager.sendRawMessage(
                            RunStateUpdate(
                                state = openClawClient.runState.value.name.lowercase(),
                                canAbort = openClawClient.runState.value !in setOf(
                                    OpenClawClient.RunState.IDLE,
                                    OpenClawClient.RunState.ERROR,
                                    OpenClawClient.RunState.ABORTING
                                ),
                                error = openClawClient.runError.value
                            ).toJson()
                        )
                        syncTalkModeStateToGlasses()
                        glassesManager.sendRawMessage(
                            LiveCaptionUpdate(
                                enabled = liveCaptionManager.state.value.enabled,
                                sourceText = liveCaptionManager.state.value.sourceText,
                                translatedText = liveCaptionManager.state.value.translatedText,
                                sourceLanguage = liveCaptionManager.state.value.sourceLanguage,
                                targetLanguage = liveCaptionManager.state.value.targetLanguage,
                                error = liveCaptionManager.state.value.error,
                            ).toJson()
                        )
                        syncScrollSettingsToGlasses()
                    }
                    "tts_toggle" -> {
                        val enabled = json.optBoolean("enabled", false)
                        android.util.Log.d("MainScreen", "TTS toggle from glasses: $enabled")
                        val effectiveEnabled = enabled && ttsSettingsManager.isConfigured()
                        ttsSettingsManager.setEnabled(effectiveEnabled)
                        // Send updated state back to glasses
                        val ttsStateMsg = TtsState(
                            enabled = effectiveEnabled,
                            voiceName = ttsSettingsManager.selectedVoiceName.value,
                            provider = ttsSettingsManager.provider.value.name.lowercase(),
                            playbackState = ttsPlaybackManager.state.value.name.lowercase(),
                            canReplay = ttsPlaybackManager.canReplay.value,
                        )
                        glassesManager.sendRawMessage(ttsStateMsg.toJson())
                    }
                    "tts_control" -> {
                        when (json.optString("action", "")) {
                            "stop" -> ttsPlaybackManager.stop()
                            "replay" -> {
                                prepareTtsPlayback()
                                ttsPlaybackManager.replay()
                            }
                        }
                    }
                    "talk_mode_toggle" -> {
                        val enabled = json.optBoolean("enabled", false)
                        if (enabled) {
                            if (liveCaptionManager.state.value.enabled) setLiveCaptionsEnabled(false)
                            talkModeManager.setEnabled(true, TalkModeSource.GLASSES)
                            startTalkListening(TalkModeSource.GLASSES, false)
                        } else {
                            stopTalkMode(disable = true)
                        }
                        syncTalkModeStateToGlasses()
                    }
                    "live_caption_toggle" -> {
                        setLiveCaptionsEnabled(json.optBoolean("enabled", false))
                    }
                    "hud_card_action" -> {
                        val cardId = json.optString("cardId")
                        when (json.optString("actionId")) {
                            "summarize" -> hudCardBodies[cardId]?.let { body ->
                                openClawClient.sendMessage("Summarize this notification concisely and identify any action needed: $body")
                            }
                            "reply" -> {
                                RokidSdkManager.setCommunicationDevice()
                                startVoiceRecognitionWithManager(
                                    voiceRecognitionManager = voiceRecognitionManager,
                                    voiceHandler = voiceHandler,
                                    openClawClient = openClawClient,
                                    glassesManager = glassesManager,
                                    mainHandler = mainHandler,
                                    isRetry = false,
                                    languageTag = voiceLanguageManager.getActiveLanguageTag(),
                                    pendingPhotos = { pendingPhotos },
                                    onPhotosConsumed = { pendingPhotos = emptyList() },
                                )
                            }
                        }
                        hudCardBodies.remove(cardId)
                    }
                    "take_photo" -> {
                        android.util.Log.d("MainScreen", "Glasses requested photo capture")
                        capturePhoto(
                            sendAfterCapture = json.optBoolean("sendAfterCapture", false),
                            visionPrompt = json.optString("visionPrompt").takeIf { it.isNotBlank() },
                        )
                    }
                    "remove_photo" -> {
                        val all = json.optBoolean("all", false)
                        val index = json.optInt("index", -1)
                        if (all) {
                            pendingPhotos = emptyList()
                        } else if (index in pendingPhotos.indices) {
                            pendingPhotos = pendingPhotos.toMutableList().apply { removeAt(index) }
                        }
                    }
                    "request_more_history" -> {
                        // Full history remains phone-side. Re-send the bounded recent snapshot
                        // for compatibility with an older glasses build requesting expansion.
                        val allMessages = openClawClient.chatMessages.value
                        android.util.Log.d(
                            "MainScreen",
                            "Glasses requested more history; returning complete recent snapshot " +
                                "from ${allMessages.size} cached messages"
                        )
                        sendHistoryToGlasses(allMessages, "legacy history request")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainScreen", "Error parsing glasses message", e)
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            // MainScreen can be recreated for configuration/lifecycle changes.
            // Stop jobs owned by this UI instance without tearing down the shared
            // Rokid SDK connection used by the foreground service.
            pendingTalkRestart?.let(mainHandler::removeCallbacks)
            pendingTalkRestart = null
            voiceRecognitionManager.onSpeechStopped = null
            glassesManager.dispose()
            openClawClient.cleanup()
            voiceHandler.cleanup()
            voiceRecognitionManager.cleanup()
            liveCaptionManager.cleanup()
            ttsPlaybackManager.dispose()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clawsses") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                // Thumbnail strip for queued photos
                if (pendingPhotos.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pendingPhotos.forEachIndexed { index, base64 ->
                            val thumbnail = remember(base64) {
                                try {
                                    val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                        ?.asImageBitmap()
                                } catch (_: Exception) { null }
                            }
                            if (thumbnail != null) {
                                Box {
                                    Image(
                                        bitmap = thumbnail,
                                        contentDescription = "Queued photo ${index + 1}",
                                        modifier = Modifier
                                            .height(56.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Fit
                                    )
                                    // Remove button
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove photo",
                                        modifier = Modifier
                                            .size(18.dp)
                                            .align(Alignment.TopEnd)
                                            .background(
                                                Color.Black.copy(alpha = 0.6f),
                                                RoundedCornerShape(9.dp)
                                            )
                                            .clickable {
                                                pendingPhotos = pendingPhotos
                                                    .toMutableList()
                                                    .apply { removeAt(index) }
                                                glassesManager.sendRawMessage(
                                                    """{"type":"remove_photo","index":$index}"""
                                                )
                                            }
                                            .padding(2.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            BottomAppBar {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("Type message...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (runState in setOf(OpenClawClient.RunState.IDLE, OpenClawClient.RunState.ERROR) &&
                                (inputText.isNotBlank() || pendingPhotos.isNotEmpty())) {
                                val hadPhotos = pendingPhotos.isNotEmpty()
                                openClawClient.sendMessage(inputText.trim(), pendingPhotos.ifEmpty { null })
                                inputText = ""
                                pendingPhotos = emptyList()
                                if (hadPhotos) {
                                    glassesManager.sendRawMessage("""{"type":"remove_photo","all":true}""")
                                }
                            }
                        }
                    ),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )

                // Camera button — always takes a new photo, adds to pending list
                IconButton(
                    onClick = {
                        android.util.Log.d("MainScreen", "Taking photo from glasses camera")
                        android.widget.Toast.makeText(context, "Capturing photo...", android.widget.Toast.LENGTH_SHORT).show()
                        capturePhoto(false)
                    },
                    enabled = glassesState is GlassesConnectionManager.ConnectionState.Connected
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = "Take photo",
                        tint = if (pendingPhotos.isNotEmpty()) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                    )
                }

                // Voice button with mode indicator
                IconButton(
                    onClick = {
                        if (talkModeState.enabled) {
                            stopTalkMode(disable = true)
                        } else if (isListening) {
                            voiceRecognitionManager.stopListening()
                        } else {
                            voiceRecognitionManager.startListening(languageTag = voiceLanguageManager.getActiveLanguageTag()) { result ->
                                when (result) {
                                    is VoiceCommandHandler.VoiceResult.Text -> {
                                        if (result.text.isNotEmpty()) {
                                            openClawClient.sendMessage(result.text)
                                        }
                                    }
                                    is VoiceCommandHandler.VoiceResult.Command -> {
                                        // Voice commands handled by glasses
                                    }
                                    is VoiceCommandHandler.VoiceResult.Error -> {
                                        // Handle error - could show toast
                                    }
                                }
                            }
                        }
                    }
                ) {
                    // Icon color indicates mode when listening:
                    // Red = listening, with tint for OpenAI (blue) vs device (red)
                    val iconTint = when {
                        talkModeState.enabled -> Color(0xFF4CAF50)
                        !isListening -> MaterialTheme.colorScheme.onSurface
                        voiceMode == VoiceRecognitionManager.RecognitionMode.OPENAI -> Color(0xFF2196F3)  // Blue for OpenAI
                        else -> Color.Red  // Red for device/fallback
                    }
                    Icon(
                        if (talkModeState.enabled || isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = when {
                            talkModeState.enabled -> "Stop Talk Mode (${talkModeState.phase.name.lowercase()})"
                            !isListening -> "Voice input"
                            voiceMode == VoiceRecognitionManager.RecognitionMode.OPENAI -> "Listening (OpenAI)"
                            else -> "Listening (Device)"
                        },
                        tint = iconTint
                    )
                }

                // Send button
                if (runState !in setOf(OpenClawClient.RunState.IDLE, OpenClawClient.RunState.ERROR)) {
                    IconButton(
                        onClick = {
                            ttsPlaybackManager.stop()
                            openClawClient.abortActiveRun()
                        },
                        enabled = runState != OpenClawClient.RunState.ABORTING
                    ) {
                        Icon(Icons.Default.StopCircle, "Stop active run")
                    }
                }

                IconButton(
                    onClick = {
                        if (inputText.isNotBlank() || pendingPhotos.isNotEmpty()) {
                            val hadPhotos = pendingPhotos.isNotEmpty()
                            openClawClient.sendMessage(inputText.trim(), pendingPhotos.ifEmpty { null })
                            inputText = ""
                            pendingPhotos = emptyList()
                            if (hadPhotos) {
                                glassesManager.sendRawMessage("""{"type":"remove_photo","all":true}""")
                            }
                        }
                    },
                    enabled = runState in setOf(OpenClawClient.RunState.IDLE, OpenClawClient.RunState.ERROR)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
            } // Column
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection status bar
            ConnectionStatusBar(
                glassesState = glassesState,
                openClawState = openClawState,
                onConnectGlasses = { glassesManager.startScanning() },
                onConnectOpenClaw = {
                    val portNum = openClawPort.toIntOrNull() ?: 18789
                    openClawClient.connect(openClawHost, portNum, openClawToken)
                }
            )

            // Session selector
            if (openClawState is OpenClawClient.ConnectionState.Connected) {
                SessionSelector(
                    sessions = sessionList,
                    currentSessionKey = currentSessionKey,
                    unreadSessionKeys = unreadSessions,
                    expanded = showSessionPicker,
                    onToggle = {
                        if (!showSessionPicker) {
                            openClawClient.requestSessions()
                        }
                        showSessionPicker = !showSessionPicker
                    },
                    onSelect = { session ->
                        showSessionPicker = false
                        openClawClient.switchSession(session.key)
                    },
                    onDismiss = { showSessionPicker = false }
                )

                AgentSelector(
                    agents = agentList,
                    currentAgentId = openClawClient.agentIdFromSessionKey(currentSessionKey),
                    expanded = showAgentPicker,
                    onToggle = {
                        if (!showAgentPicker) openClawClient.requestAgents()
                        showAgentPicker = !showAgentPicker
                    },
                    onSelect = { agent ->
                        showAgentPicker = false
                        openClawClient.switchAgent(agent.id, agent.name)
                    },
                    onDismiss = { showAgentPicker = false }
                )

                ModelSelector(
                    models = modelList,
                    currentModelRef = currentModelRef,
                    expanded = showModelPicker,
                    selecting = isSelectingModel,
                    error = modelSelectionError,
                    onToggle = {
                        if (!showModelPicker) openClawClient.requestModels()
                        showModelPicker = !showModelPicker
                    },
                    onSelect = { model ->
                        showModelPicker = false
                        openClawClient.selectModel(model)
                    },
                    onDismiss = { showModelPicker = false }
                )
            }

            // Chat messages
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (chatMessages.isEmpty()) {
                    Text(
                        "No messages yet. Connect to OpenClaw and send a message.",
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(chatMessages) { msg ->
                            ChatMessageRow(msg)
                        }
                    }
                }
            }
        }
    }

    // Glasses state for settings
    val debugModeEnabled by glassesManager.debugModeEnabled.collectAsState()
    val discoveredDevices by glassesManager.discoveredDevices.collectAsState()
    val wifiP2PConnected by glassesManager.wifiP2PConnected.collectAsState()
    var hasCachedSn by remember { mutableStateOf(RokidSdkManager.hasCachedSn()) }
    var cachedSn by remember { mutableStateOf(RokidSdkManager.getCachedSn()) }
    var cachedDeviceName by remember { mutableStateOf(RokidSdkManager.getCachedDeviceName()) }
    val sdkConnected = glassesState is GlassesConnectionManager.ConnectionState.Connected && !debugModeEnabled

    // Settings screen (full-screen overlay with slide-up animation)
    AnimatedVisibility(
        visible = showSettings,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        SettingsScreen(
            // Server
            openClawHost = openClawHost,
            openClawPort = openClawPort,
            openClawToken = openClawToken,
            openClawState = openClawState,
            onApplyServerSettings = { host, port, token ->
                openClawHost = host
                openClawPort = port
                openClawToken = token
                prefs.edit()
                    .putString("openclaw_host", host)
                    .putString("openclaw_port", port)
                    .putString("openclaw_token", token)
                    .apply()
                openClawClient.disconnect()
            },
            // Glasses
            glassesState = glassesState,
            discoveredDevices = discoveredDevices,
            wifiP2PConnected = wifiP2PConnected,
            debugModeEnabled = debugModeEnabled,
            onStartScanning = { glassesManager.startScanning() },
            onStopScanning = { glassesManager.stopScanning() },
            onConnectDevice = { device -> glassesManager.connectToDevice(device) },
            onDisconnectGlasses = { glassesManager.disconnect() },
            onInitWifiP2P = { glassesManager.initWifiP2P() },
            onClearSn = {
                RokidSdkManager.clearCachedSn()
                hasCachedSn = false
                cachedSn = null
                cachedDeviceName = null
            },
            onCancelReconnect = { glassesManager.cancelReconnect() },
            onRetryReconnect = { glassesManager.retryReconnectNow() },
            hasCachedSn = hasCachedSn,
            cachedSn = cachedSn,
            cachedDeviceName = cachedDeviceName,
            // Wake on stream
            wakeOnStreamEnabled = wakeOnStreamEnabled,
            onWakeOnStreamChange = { enabled ->
                glassesManager.wakeSignalManager.setEnabled(enabled)
            },
            savePhotosToGallery = savePhotosToGallery,
            onSavePhotosToGalleryChange = { enabled ->
                savePhotosToGallery = enabled
                prefs.edit().putBoolean("save_photos_to_gallery", enabled).apply()
            },
            scrollMessagesPerStep = scrollMessagesPerStep,
            onScrollMessagesPerStepChange = { requestedStep ->
                val normalizedStep = ScrollSettings.normalizeMessagesPerStep(requestedStep)
                scrollMessagesPerStep = normalizedStep
                prefs.edit().putInt("scroll_messages_per_step", normalizedStep).apply()
                syncScrollSettingsToGlasses(normalizedStep)
            },
            onSwitchToHiRokid = { switchToHiRokid() },
            // Software Update
            installState = installState,
            sdkConnected = sdkConnected,
            onInstall = { apkInstaller.installViaSdk() },
            onCancelInstall = { apkInstaller.cancelInstallation() },
            // Voice
            voiceLanguageManager = voiceLanguageManager,
            voiceRecognitionManager = voiceRecognitionManager,
            talkModeState = talkModeState,
            onTalkModeChange = { enabled ->
                if (enabled) {
                    if (liveCaptionManager.state.value.enabled) setLiveCaptionsEnabled(false)
                    val source = if (glassesState is GlassesConnectionManager.ConnectionState.Connected) {
                        TalkModeSource.GLASSES
                    } else {
                        TalkModeSource.PHONE
                    }
                    talkModeManager.setEnabled(true, source)
                    startTalkListening(source, false)
                } else {
                    stopTalkMode(disable = true)
                }
            },
            liveCaptionsEnabled = liveCaptionState.enabled,
            translateCaptions = translateCaptions,
            captionTargetLanguage = captionTargetLanguage,
            onLiveCaptionsChange = { enabled -> setLiveCaptionsEnabled(enabled) },
            onTranslateCaptionsChange = { enabled ->
                translateCaptions = enabled
                prefs.edit().putBoolean("translate_captions", enabled).apply()
                liveCaptionManager.updateTranslationConfig(captionTargetLanguage, enabled)
            },
            onCaptionTargetLanguageChange = { language ->
                captionTargetLanguage = language
                prefs.edit().putString("caption_target_language", language).apply()
                liveCaptionManager.updateTranslationConfig(language, translateCaptions)
            },
            // TTS
            ttsSettingsManager = ttsSettingsManager,
            elevenLabsClient = elevenLabsClient,
            ttsPlaybackState = ttsPlaybackState,
            ttsCanReplay = ttsCanReplay,
            onTtsStop = { ttsPlaybackManager.stop() },
            onTtsReplay = { ttsPlaybackManager.replay() },
            // Developer
            onDebugModeChange = { enabled ->
                if (enabled) glassesManager.enableDebugMode()
                else glassesManager.disableDebugMode()
            },
            // Navigation
            onBack = {
                showSettings = false
                glassesManager.stopScanning()
                if (installState is ApkInstaller.InstallState.Success ||
                    installState is ApkInstaller.InstallState.Error) {
                    apkInstaller.resetState()
                }
            },
        )
    }
    } // Box
}

@Composable
fun ChatMessageRow(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .background(
                    if (isUser) Color(0xFF2A3A2A) else Color.Transparent,
                    shape = MaterialTheme.shapes.small
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .fillMaxWidth(0.85f)
        ) {
            msg.attachments.forEachIndexed { index, attachment ->
                val image = remember(attachment.base64) {
                    attachment.base64?.let { decodeBase64Image(it, 960, 720)?.asImageBitmap() }
                }
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = attachment.fileName ?: "Attached image ${index + 1}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    if (index < msg.attachments.lastIndex || msg.content.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            if (msg.content.isNotBlank()) {
                Text(
                    text = msg.content,
                    color = if (isUser) Color(0xFF4EC9B0) else Color(0xFFD4D4D4),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusBar(
    glassesState: GlassesConnectionManager.ConnectionState,
    openClawState: OpenClawClient.ConnectionState,
    onConnectGlasses: () -> Unit,
    onConnectOpenClaw: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Glasses status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                when (glassesState) {
                    is GlassesConnectionManager.ConnectionState.Connected -> Icons.Default.CheckCircle
                    is GlassesConnectionManager.ConnectionState.Connecting,
                    is GlassesConnectionManager.ConnectionState.Scanning,
                    is GlassesConnectionManager.ConnectionState.Reconnecting -> Icons.Default.Sync
                    is GlassesConnectionManager.ConnectionState.Error -> Icons.Default.Error
                    else -> Icons.Default.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = when (glassesState) {
                    is GlassesConnectionManager.ConnectionState.Connected -> Color.Green
                    is GlassesConnectionManager.ConnectionState.Connecting,
                    is GlassesConnectionManager.ConnectionState.Scanning -> Color.Yellow
                    is GlassesConnectionManager.ConnectionState.Reconnecting -> Color(0xFFFFA500) // Orange
                    is GlassesConnectionManager.ConnectionState.Error -> Color.Red
                    else -> Color.Gray
                },
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Visibility,
                contentDescription = "Glasses",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            if (glassesState is GlassesConnectionManager.ConnectionState.Disconnected) {
                TextButton(
                    onClick = onConnectGlasses,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Connect", fontSize = 12.sp)
                }
            } else {
                Text(
                    text = when (glassesState) {
                        is GlassesConnectionManager.ConnectionState.Connected -> "Connected"
                        is GlassesConnectionManager.ConnectionState.Connecting -> "Connecting..."
                        is GlassesConnectionManager.ConnectionState.Scanning -> "Scanning..."
                        is GlassesConnectionManager.ConnectionState.Reconnecting -> {
                            val state = glassesState as GlassesConnectionManager.ConnectionState.Reconnecting
                            "Reconnecting (#${state.attempt})..."
                        }
                        is GlassesConnectionManager.ConnectionState.Error -> "Error"
                        else -> ""
                    },
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }

        // OpenClaw status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.weight(1f)
        ) {
            if (openClawState is OpenClawClient.ConnectionState.Disconnected) {
                TextButton(
                    onClick = onConnectOpenClaw,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Connect", fontSize = 12.sp)
                }
            } else {
                Text(
                    text = when (openClawState) {
                        is OpenClawClient.ConnectionState.Connected -> "Connected"
                        is OpenClawClient.ConnectionState.Connecting -> "Connecting..."
                        is OpenClawClient.ConnectionState.Authenticating -> "Authenticating..."
                        is OpenClawClient.ConnectionState.PairingRequired -> "Pairing required"
                        is OpenClawClient.ConnectionState.Error -> (openClawState as OpenClawClient.ConnectionState.Error).message.take(40)
                        else -> ""
                    },
                    fontSize = 11.sp,
                    color = when (openClawState) {
                        is OpenClawClient.ConnectionState.Error -> Color.Red
                        is OpenClawClient.ConnectionState.PairingRequired -> Color(0xFFFF8800)
                        else -> Color.Gray
                    }
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Cloud,
                contentDescription = "OpenClaw",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                when (openClawState) {
                    is OpenClawClient.ConnectionState.Connected -> Icons.Default.CheckCircle
                    is OpenClawClient.ConnectionState.Connecting,
                    is OpenClawClient.ConnectionState.Authenticating -> Icons.Default.Sync
                    is OpenClawClient.ConnectionState.PairingRequired -> Icons.Default.Warning
                    is OpenClawClient.ConnectionState.Error -> Icons.Default.Error
                    else -> Icons.Default.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = when (openClawState) {
                    is OpenClawClient.ConnectionState.Connected -> Color.Green
                    is OpenClawClient.ConnectionState.Connecting,
                    is OpenClawClient.ConnectionState.Authenticating -> Color.Yellow
                    is OpenClawClient.ConnectionState.PairingRequired -> Color(0xFFFF8800)
                    is OpenClawClient.ConnectionState.Error -> Color.Red
                    else -> Color.Gray
                },
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun SessionSelector(
    sessions: List<SessionInfo>,
    currentSessionKey: String?,
    unreadSessionKeys: Set<String> = emptySet(),
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (SessionInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val currentSession = sessions.firstOrNull { it.key == currentSessionKey }
    val displayName = currentSession?.name ?: currentSessionKey ?: "No session"
    val hasAnyUnread = unreadSessionKeys.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Forum,
                contentDescription = "Session",
                modifier = Modifier.size(18.dp),
                tint = if (hasAnyUnread) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            if (hasAnyUnread) {
                Icon(
                    Icons.Default.Circle,
                    contentDescription = "Unread messages in other sessions",
                    modifier = Modifier.size(8.dp),
                    tint = Color(0xFF4CAF50)
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            if (sessions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Loading sessions...") },
                    onClick = {},
                    enabled = false
                )
            } else {
                sessions.forEach { session ->
                    val isCurrent = session.key == currentSessionKey
                    val hasUnread = session.key in unreadSessionKeys
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isCurrent) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Current",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                } else if (hasUnread) {
                                    Icon(
                                        Icons.Default.Circle,
                                        contentDescription = "New messages",
                                        modifier = Modifier.size(10.dp),
                                        tint = Color(0xFF4CAF50)
                                    )
                                    Spacer(Modifier.width(11.dp))
                                }
                                Text(
                                    text = session.name,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                                            else if (hasUnread) Color(0xFF4CAF50)
                                            else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        },
                        onClick = { onSelect(session) }
                    )
                }
            }
        }
    }
}

@Composable
fun AgentSelector(
    agents: List<AgentInfo>,
    currentAgentId: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (AgentInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val currentAgent = agents.firstOrNull { it.id == currentAgentId }
    val displayName = currentAgent?.name ?: currentAgentId ?: "No agent"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.SmartToy,
                contentDescription = "Agent",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            if (agents.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Loading agents...") },
                    onClick = {},
                    enabled = false
                )
            } else {
                agents.forEach { agent ->
                    val isCurrent = agent.id == currentAgentId
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isCurrent) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Current",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column {
                                    Text(
                                        text = agent.name,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    agent.model?.takeIf { it.isNotBlank() }?.let { model ->
                                        Text(
                                            text = model,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        },
                        onClick = { onSelect(agent) }
                    )
                }
            }
        }
    }
}

@Composable
fun ModelSelector(
    models: List<ModelInfo>,
    currentModelRef: String?,
    expanded: Boolean,
    selecting: Boolean,
    error: String?,
    onToggle: () -> Unit,
    onSelect: (ModelInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentModel = models.firstOrNull { it.ref == currentModelRef }
    val displayName = currentModel?.name ?: currentModelRef ?: "Select model"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !selecting, onClick = onToggle)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Model",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selecting) "Changing model..." else displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                    currentModelRef?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            maxLines = 1
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse models" else "Expand models",
                    modifier = Modifier.size(20.dp)
                )
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Loading models...") },
                    onClick = {},
                    enabled = false
                )
            } else {
                models.forEach { model ->
                    val isCurrent = model.ref == currentModelRef
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isCurrent) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Current model",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Column {
                                    Text(
                                        text = model.name,
                                        color = when {
                                            !model.available -> Color.Gray
                                            isCurrent -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1
                                    )
                                    Text(
                                        text = if (model.available) model.ref else "${model.ref} (unavailable)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray,
                                        maxLines = 1
                                    )
                                }
                            }
                        },
                        onClick = { onSelect(model) },
                        enabled = model.available && !selecting && !isCurrent,
                    )
                }
            }
        }
    }
}

/**
 * Start voice recognition using VoiceRecognitionManager (OpenAI with fallback).
 */
private fun startVoiceRecognitionWithManager(
    voiceRecognitionManager: VoiceRecognitionManager,
    voiceHandler: VoiceCommandHandler,
    openClawClient: OpenClawClient,
    glassesManager: GlassesConnectionManager,
    mainHandler: android.os.Handler,
    isRetry: Boolean,
    languageTag: String? = null,
    pendingPhotos: () -> List<String> = { emptyList() },
    onPhotosConsumed: () -> Unit = {}
) {
    // Send initial voice state with mode indicator
    val modeIndicator = if (voiceRecognitionManager.isOpenAIAvailable()) "openai" else "device"
    val stateMsg = org.json.JSONObject().apply {
        put("type", "voice_state")
        put("state", "listening")
        put("mode", modeIndicator)
    }
    glassesManager.sendRawMessage(stateMsg.toString())

    // Keep the SDK AI scene alive — it times out if no ASR content is sent.
    // With OpenAI Realtime, there are no partials during active speech (only after VAD pause),
    // so the AI scene would close before any transcription arrives. Sending initial content
    // resets the timeout. Real partial results replace this via onPartialResult.
    RokidSdkManager.sendAsrContent("...")

    // Send "processing" state to glasses when VAD detects speech end
    voiceRecognitionManager.onSpeechStopped = {
        val processingMsg = org.json.JSONObject().apply {
            put("type", "voice_state")
            put("state", "processing")
            put("mode", modeIndicator)
        }
        glassesManager.sendRawMessage(processingMsg.toString())
    }

    voiceRecognitionManager.startListening(languageTag = languageTag) { result ->
        val actualMode = voiceRecognitionManager.getModeDescription()
        android.util.Log.i("MainScreen", ">>> Voice result received (mode=$actualMode, retry=$isRetry)")

        when (result) {
            is VoiceCommandHandler.VoiceResult.Text -> {
                RokidSdkManager.clearCommunicationDevice()
                if (result.text.isNotEmpty()) {
                    android.util.Log.i("MainScreen", "Voice text received ($actualMode, ${result.text.length} chars)")
                    RokidSdkManager.sendAsrContent(result.text)
                    RokidSdkManager.notifyAsrEnd()
                    // Don't send to OpenClaw here — glasses stages the text
                    // and sends user_input when user confirms via Send button
                    val resultMsg = org.json.JSONObject().apply {
                        put("type", "voice_result")
                        put("result_type", "text")
                        put("text", result.text)
                    }
                    glassesManager.sendRawMessage(resultMsg.toString())
                    mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 1500)
                } else {
                    android.util.Log.i("MainScreen", "Voice: no speech detected, dismissing")
                    RokidSdkManager.notifyAsrNone()
                    // Send voice_state idle to glasses so the voice overlay closes
                    val idleMsg = org.json.JSONObject().apply {
                        put("type", "voice_state")
                        put("state", "idle")
                    }
                    glassesManager.sendRawMessage(idleMsg.toString())
                    mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 500)
                }
            }
            is VoiceCommandHandler.VoiceResult.Command -> {
                RokidSdkManager.clearCommunicationDevice()
                android.util.Log.i("MainScreen", "Voice command ($actualMode): ${result.command}")
                RokidSdkManager.sendAsrContent(result.command)
                RokidSdkManager.notifyAsrEnd()
                val resultMsg = org.json.JSONObject().apply {
                    put("type", "voice_result")
                    put("result_type", "command")
                    put("text", result.command)
                }
                glassesManager.sendRawMessage(resultMsg.toString())
                mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 1000)
            }
            is VoiceCommandHandler.VoiceResult.Error -> {
                // VoiceRecognitionManager handles fallback internally, but if we still get an error
                // after fallback attempt, we can retry with phone mic as last resort
                if (!isRetry) {
                    android.util.Log.w("MainScreen", "Voice recognition failed; retrying with phone mic")
                    RokidSdkManager.clearCommunicationDevice()
                    mainHandler.postDelayed({
                        startVoiceRecognition(voiceHandler, openClawClient, glassesManager, mainHandler, isRetry = true, languageTag = languageTag, pendingPhotos = pendingPhotos, onPhotosConsumed = onPhotosConsumed)
                    }, 200)
                } else {
                    android.util.Log.e("MainScreen", "Voice recognition failed after retry")
                    RokidSdkManager.clearCommunicationDevice()
                    RokidSdkManager.notifyAsrError()
                    val resultMsg = org.json.JSONObject().apply {
                        put("type", "voice_result")
                        put("result_type", "error")
                        put("text", result.message)
                    }
                    glassesManager.sendRawMessage(resultMsg.toString())
                    mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 2000)
                }
            }
        }
    }
}

/**
 * Start voice recognition with automatic retry on error (fallback handler only).
 */
private fun startVoiceRecognition(
    voiceHandler: VoiceCommandHandler,
    openClawClient: OpenClawClient,
    glassesManager: GlassesConnectionManager,
    mainHandler: android.os.Handler,
    isRetry: Boolean,
    languageTag: String? = null,
    pendingPhotos: () -> List<String> = { emptyList() },
    onPhotosConsumed: () -> Unit = {}
) {
    voiceHandler.startListening(languageTag = languageTag) { result ->
        android.util.Log.i("MainScreen", ">>> Voice result received (retry=$isRetry)")
        when (result) {
            is VoiceCommandHandler.VoiceResult.Text -> {
                RokidSdkManager.clearCommunicationDevice()
                if (result.text.isNotEmpty()) {
                    android.util.Log.i("MainScreen", "AI voice text received (${result.text.length} chars)")
                    RokidSdkManager.sendAsrContent(result.text)
                    RokidSdkManager.notifyAsrEnd()
                    // Don't send to OpenClaw here — glasses stages the text
                    // and sends user_input when user confirms via Send button
                    val resultMsg = org.json.JSONObject().apply {
                        put("type", "voice_result")
                        put("result_type", "text")
                        put("text", result.text)
                    }
                    glassesManager.sendRawMessage(resultMsg.toString())
                    mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 1500)
                } else {
                    android.util.Log.i("MainScreen", "AI voice: no speech detected, dismissing")
                    RokidSdkManager.notifyAsrNone()
                    val idleMsg = org.json.JSONObject().apply {
                        put("type", "voice_state")
                        put("state", "idle")
                    }
                    glassesManager.sendRawMessage(idleMsg.toString())
                    mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 500)
                }
            }
            is VoiceCommandHandler.VoiceResult.Command -> {
                RokidSdkManager.clearCommunicationDevice()
                android.util.Log.i("MainScreen", "AI voice command: ${result.command}")
                RokidSdkManager.sendAsrContent(result.command)
                RokidSdkManager.notifyAsrEnd()
                val resultMsg = org.json.JSONObject().apply {
                    put("type", "voice_result")
                    put("result_type", "command")
                    put("text", result.command)
                }
                glassesManager.sendRawMessage(resultMsg.toString())
                mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 1000)
            }
            is VoiceCommandHandler.VoiceResult.Error -> {
                if (!isRetry) {
                    android.util.Log.w("MainScreen", "AI voice recognition failed; retrying with phone mic")
                    RokidSdkManager.clearCommunicationDevice()
                    mainHandler.postDelayed({
                        startVoiceRecognition(voiceHandler, openClawClient, glassesManager, mainHandler, isRetry = true, languageTag = languageTag, pendingPhotos = pendingPhotos, onPhotosConsumed = onPhotosConsumed)
                    }, 200)
                } else {
                    android.util.Log.e("MainScreen", "AI voice recognition failed after retry")
                    RokidSdkManager.clearCommunicationDevice()
                    RokidSdkManager.notifyAsrError()
                    val resultMsg = org.json.JSONObject().apply {
                        put("type", "voice_result")
                        put("result_type", "error")
                        put("text", result.message)
                    }
                    glassesManager.sendRawMessage(resultMsg.toString())
                    mainHandler.postDelayed({ RokidSdkManager.sendExitEvent() }, 2000)
                }
            }
        }
    }
}

/**
 * Build a chat_history JSON message for sending to glasses.
 * Truncates long messages and limits total size for CXR/Bluetooth safety.
 *
 * @param maxMessages How many most-recent messages to include (default 20)
 * @param isLoadMore  If true, glasses will adjust scroll position instead of jumping to bottom
 * @param hasMore     Whether even older messages exist beyond what's being sent
 */
private fun buildChatHistoryJson(
    messages: List<ChatMessage>,
    maxMessages: Int = 20,
    isLoadMore: Boolean = false,
    hasMore: Boolean = true
): String {
    val maxContentLength = 2000
    val recentMessages = if (messages.size > maxMessages) messages.takeLast(maxMessages) else messages

    return org.json.JSONObject().apply {
        put("type", "chat_history")
        if (isLoadMore) {
            put("isLoadMore", true)
            put("hasMore", hasMore)
        }
        val arr = org.json.JSONArray()
        for (msg in recentMessages) {
            arr.put(org.json.JSONObject().apply {
                put("id", msg.id)
                put("role", msg.role)
                put("content", if (msg.content.length > maxContentLength)
                    msg.content.take(maxContentLength) + "..." else msg.content)
                put("timestamp", msg.timestamp)
                putThumbnailAttachments(this, msg)
            })
        }
        put("messages", arr)
    }.toString()
}

/**
 * Build a CXR-safe chat message. Full image data stays on the phone; the glasses receive
 * only small display thumbnails so a photo cannot overflow the Bluetooth command channel.
 */
private fun buildGlassesChatMessageJson(message: ChatMessage): String =
    org.json.JSONObject().apply {
        put("type", "chat_message")
        put("id", message.id)
        put("role", message.role)
        put("content", message.content.take(2000))
        put("timestamp", message.timestamp)
        putThumbnailAttachments(this, message)
    }.toString()

private fun putThumbnailAttachments(target: org.json.JSONObject, message: ChatMessage) {
    val attachments = org.json.JSONArray()
    message.attachments.take(4).forEach { attachment ->
        val bytes = decodeBase64Bytes(attachment.base64) ?: return@forEach
        val thumbnail = createThumbnailBase64(bytes, 80, 60)
        if (thumbnail.isNotBlank()) {
            attachments.put(org.json.JSONObject().apply {
                put("type", "image")
                put("mimeType", "image/webp")
                put("fileName", attachment.fileName ?: "photo")
                put("thumbnail", thumbnail)
            })
        }
    }
    if (attachments.length() > 0) target.put("attachments", attachments)
}

private fun decodeBase64Bytes(encoded: String?): ByteArray? {
    if (encoded.isNullOrBlank()) return null
    val payload = encoded.substringAfter(',', encoded)
    return runCatching {
        android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
    }.getOrNull()
}

private fun decodeBase64Image(encoded: String, maxWidth: Int, maxHeight: Int): android.graphics.Bitmap? {
    val bytes = decodeBase64Bytes(encoded) ?: return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxWidth * 2 || bounds.outHeight / sampleSize > maxHeight * 2) {
        sampleSize *= 2
    }
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun createThumbnailBase64(imageBytes: ByteArray, maxWidth: Int, maxHeight: Int): String {
    val encoded = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
    val bitmap = decodeBase64Image(encoded, maxWidth, maxHeight)
        ?: return ""
    val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
    val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
    // Convert to high-contrast grayscale for the monochrome green glasses display.
    // Store luminance in alpha channel so glasses can tint it green.
    val grayscale = android.graphics.Bitmap.createBitmap(scaled.width, scaled.height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(grayscale)
    val paint = android.graphics.Paint()
    // Grayscale color matrix
    val cm = android.graphics.ColorMatrix()
    cm.setSaturation(0f)
    // Boost contrast: scale by 1.8, offset by -100
    val contrast = android.graphics.ColorMatrix(floatArrayOf(
        1.8f, 0f, 0f, 0f, -100f,
        0f, 1.8f, 0f, 0f, -100f,
        0f, 0f, 1.8f, 0f, -100f,
        0f, 0f, 0f, 1f, 0f
    ))
    cm.postConcat(contrast)
    paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
    canvas.drawBitmap(scaled, 0f, 0f, paint)
    if (scaled !== bitmap) bitmap.recycle()
    scaled.recycle()
    val stream = java.io.ByteArrayOutputStream()
    grayscale.compress(android.graphics.Bitmap.CompressFormat.WEBP, 60, stream)
    grayscale.recycle()
    return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
}
