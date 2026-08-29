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
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clawsses.phone.ClawssesApp
import com.clawsses.phone.glasses.ApkInstaller
import com.clawsses.phone.glasses.GlassesConnectionManager
import com.clawsses.phone.glasses.RokidSdkManager
import com.clawsses.phone.media.ImagePipeline
import com.clawsses.phone.media.PendingPhoto
import com.clawsses.phone.openclaw.OpenClawClient
import com.clawsses.phone.talk.TalkInteractionMode
import com.clawsses.phone.talk.TalkModeSource
import com.clawsses.phone.ui.settings.SettingsScreen
import com.clawsses.phone.util.SecurePreferences
import com.clawsses.phone.voice.VoiceCommandHandler
import com.clawsses.phone.voice.VoiceRecognitionManager
import com.clawsses.shared.AgentInfo
import com.clawsses.shared.ChatMessage
import com.clawsses.shared.ChatScrollCoordinator
import com.clawsses.shared.ModelInfo
import com.clawsses.shared.SessionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val runtime = remember(context) {
        (context.applicationContext as ClawssesApp).runtime
    }

    // Process-scoped managers survive Activity and Compose recreation.
    val glassesManager = runtime.glassesManager
    val openClawClient = runtime.openClawClient
    val voiceLanguageManager = runtime.voiceLanguageManager
    val voiceRecognitionManager = runtime.voiceRecognitionManager
    val liveCaptionManager = runtime.liveCaptionManager
    val talkModeManager = runtime.talkModeManager
    val apkInstaller = runtime.apkInstaller
    val ttsSettingsManager = runtime.ttsSettingsManager
    val elevenLabsClient = runtime.elevenLabsClient
    val ttsPlaybackManager = runtime.ttsPlaybackManager

    // State
    val glassesState by glassesManager.connectionState.collectAsStateWithLifecycle()
    val openClawState by openClawClient.connectionState.collectAsStateWithLifecycle()
    val runState by openClawClient.runState.collectAsStateWithLifecycle()
    val runError by openClawClient.runError.collectAsStateWithLifecycle()
    val talkModeState by talkModeManager.state.collectAsStateWithLifecycle()
    val isListening by voiceRecognitionManager.isListening.collectAsStateWithLifecycle()
    val voiceMode by voiceRecognitionManager.activeMode.collectAsStateWithLifecycle()
    val glassesWakeState by glassesManager.wakeSignalManager.wakeState.collectAsStateWithLifecycle()
    val ttsEnabled by ttsSettingsManager.isEnabled.collectAsStateWithLifecycle()
    val ttsVoiceName by ttsSettingsManager.selectedVoiceName.collectAsStateWithLifecycle()
    val ttsProvider by ttsSettingsManager.provider.collectAsStateWithLifecycle()
    val ttsPlaybackState by ttsPlaybackManager.state.collectAsStateWithLifecycle()
    val ttsCanReplay by ttsPlaybackManager.canReplay.collectAsStateWithLifecycle()
    val liveCaptionState by liveCaptionManager.state.collectAsStateWithLifecycle()

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
    var translateCaptions by remember {
        mutableStateOf(prefs.getBoolean("translate_captions", false))
    }
    var captionTargetLanguage by remember {
        mutableStateOf(prefs.getString("caption_target_language", "English") ?: "English")
    }
    var inputText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val pendingPhotos by runtime.pendingPhotos.collectAsStateWithLifecycle()
    val uiScope = rememberCoroutineScope()
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun stopTalkMode(disable: Boolean) {
        runtime.talkCoordinator.stopTalkMode(disable)
    }

    fun setLiveCaptionsEnabled(enabled: Boolean) {
        runtime.phoneGlassesBridge.setLiveCaptionsEnabled(enabled)
    }

    fun capturePhoto(sendAfterCapture: Boolean, visionPrompt: String? = null) =
        runtime.phoneGlassesBridge.capturePhoto(sendAfterCapture, visionPrompt)

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

    fun startTalkListening(source: TalkModeSource, interruptCurrent: Boolean) =
        runtime.talkCoordinator.startListening(source, interruptCurrent)

    fun sendQueuedMessage(rawText: String) {
        uiScope.launch {
            val images = runtime.pendingPhotoRepository.consumeEncoded().ifEmpty { null }
            val text = rawText.trim()
            if (text.isNotEmpty() || images != null) {
                openClawClient.sendMessage(text, images)
            }
            if (images != null) {
                glassesManager.sendRawMessage("""{"type":"remove_photo","all":true}""")
            }
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
                        pendingPhotos.forEachIndexed { index, photo ->
                            val thumbnail = rememberDecodedPhoto(photo, 320, 240)
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
                                                uiScope.launch {
                                                    runtime.pendingPhotoRepository.removeAt(index)
                                                    glassesManager.sendRawMessage(
                                                        """{"type":"remove_photo","index":$index}"""
                                                    )
                                                }
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
                                sendQueuedMessage(inputText)
                                inputText = ""
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
                            sendQueuedMessage(inputText)
                            inputText = ""
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
                MainCatalogControls(openClawClient)
            }

            PhoneChatPane(
                openClawClient = openClawClient,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }

    // Settings screen (full-screen overlay with slide-up animation)
    AnimatedVisibility(
        visible = showSettings,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        // Settings-only flows are collected only while the overlay is composed.
        val installState by apkInstaller.installState.collectAsStateWithLifecycle()
        val wakeOnStreamEnabled by
            glassesManager.wakeSignalManager.enabled.collectAsStateWithLifecycle()
        val debugModeEnabled by glassesManager.debugModeEnabled.collectAsStateWithLifecycle()
        val discoveredDevices by glassesManager.discoveredDevices.collectAsStateWithLifecycle()
        val wifiP2PConnected by glassesManager.wifiP2PConnected.collectAsStateWithLifecycle()
        var hasCachedSn by remember { mutableStateOf(RokidSdkManager.hasCachedSn()) }
        var cachedSn by remember { mutableStateOf(RokidSdkManager.getCachedSn()) }
        var cachedDeviceName by remember { mutableStateOf(RokidSdkManager.getCachedDeviceName()) }
        val sdkConnected =
            glassesState is GlassesConnectionManager.ConnectionState.Connected && !debugModeEnabled

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
            onSwitchToHiRokid = { switchToHiRokid() },
            onRestartGlasses = {
                if (liveCaptionManager.state.value.enabled) setLiveCaptionsEnabled(false)
                runtime.talkCoordinator.prepareForGlassesRestart()
                RokidSdkManager.restartGlasses()
            },
            // Software Update
            installState = installState,
            sdkConnected = sdkConnected,
            onInstall = { apkInstaller.installViaSdk() },
            onInstallViaHiRokid = { apkInstaller.installViaHiRokid() },
            onVerifyInstall = { apkInstaller.retryPendingVerification() },
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
                    if (talkModeManager.state.value.interactionMode ==
                        TalkInteractionMode.ALWAYS_LISTENING
                    ) {
                        startTalkListening(source, false)
                    } else {
                        runtime.talkCoordinator.syncTalkModeStateToGlasses()
                    }
                } else {
                    stopTalkMode(disable = true)
                }
            },
            onTalkInteractionModeChange = runtime.talkCoordinator::setInteractionMode,
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
            onTtsReplay = {
                if (liveCaptionManager.state.value.enabled) setLiveCaptionsEnabled(false)
                runtime.talkCoordinator.prepareTtsPlayback()
                ttsPlaybackManager.replay()
            },
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

/** Streaming chat owns its own lifecycle-aware state and no longer invalidates MainScreen. */
@Composable
private fun PhoneChatPane(
    openClawClient: OpenClawClient,
    modifier: Modifier = Modifier,
) {
    val messages by openClawClient.chatMessages.collectAsStateWithLifecycle()
    val loadingMore by openClawClient.isLoadingMoreHistory.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scrollCoordinator = remember { ChatScrollCoordinator() }
    var previousFirstMessageId by remember { mutableStateOf<String?>(null) }
    var visibleAnchorId by remember { mutableStateOf<String?>(null) }
    var visibleAnchorOffset by remember { mutableIntStateOf(0) }
    val listDragged by listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(listDragged, listState.canScrollForward) {
        val isAtBottom = !listState.canScrollForward
        scrollCoordinator.onViewportChanged(atEnd = isAtBottom, userDriven = listDragged)
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            (visible?.key as? String) to listState.firstVisibleItemScrollOffset
        }.collect { (id, offset) ->
            if (id != null) {
                visibleAnchorId = id
                visibleAnchorOffset = offset
            }
        }
    }

    val tailVersion = messages.lastOrNull()?.let { "${it.id}:${it.content.length}" }
    LaunchedEffect(messages.size, tailVersion) {
        if (messages.isEmpty()) return@LaunchedEffect
        val currentFirstId = messages.first().id
        val wasPrepend = previousFirstMessageId != null && currentFirstId != previousFirstMessageId
        if (wasPrepend) {
            val anchorIndex = visibleAnchorId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
            if (anchorIndex >= 0) {
                scrollCoordinator.beginHistoryRestore()
                listState.scrollToItem(anchorIndex, visibleAnchorOffset)
                scrollCoordinator.finishHistoryRestore(atEnd = !listState.canScrollForward)
            }
        } else if (scrollCoordinator.shouldFollowNewContent()) {
            scrollToTrueEnd(listState, messages.lastIndex, animated = false)
        }
        previousFirstMessageId = currentFirstId
    }

    val canScrollBackward by remember { derivedStateOf { listState.canScrollBackward } }
    LaunchedEffect(canScrollBackward) {
        if (!canScrollBackward && messages.isNotEmpty() && !loadingMore) {
            openClawClient.loadMoreHistory()
        }
    }

    Box(modifier = modifier) {
        if (messages.isEmpty()) {
            Text(
                text = "No messages yet. Connect to OpenClaw and send a message.",
                color = Color.Gray,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(messages, key = { it.id }) { message ->
                    ChatMessageRow(message)
                }
            }
        }
    }
}

private suspend fun scrollToTrueEnd(
    listState: androidx.compose.foundation.lazy.LazyListState,
    lastIndex: Int,
    animated: Boolean,
) {
    if (lastIndex < 0) return
    if (animated) listState.animateScrollToItem(lastIndex) else listState.scrollToItem(lastIndex)
    yield()
    repeat(32) {
        if (!listState.canScrollForward) return
        val viewport = listState.layoutInfo.viewportSize.height.coerceAtLeast(1).toFloat()
        val consumed = if (animated) listState.animateScrollBy(viewport) else listState.scrollBy(viewport)
        if (consumed == 0f) return
    }
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
                val image = rememberDecodedImage(attachment, 960, 720)
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

@Composable
private fun rememberDecodedImage(
    attachment: com.clawsses.shared.ChatAttachment,
    maxWidth: Int,
    maxHeight: Int,
): ImageBitmap? {
    val decoded by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = attachment.localPath ?: attachment.base64,
        key2 = maxWidth,
        key3 = maxHeight,
    ) {
        value = withContext(Dispatchers.IO) {
            val bytes = attachment.localPath?.let { path ->
                runCatching { File(path).readBytes() }.getOrNull()
            }
            if (bytes != null) {
                ImagePipeline.decodeImageBytes(bytes, maxWidth, maxHeight)?.asImageBitmap()
            } else {
                ImagePipeline.decodeBase64Image(attachment.base64, maxWidth, maxHeight)?.asImageBitmap()
            }
        }
    }
    return decoded
}

@Composable
private fun rememberDecodedPhoto(
    photo: PendingPhoto,
    maxWidth: Int,
    maxHeight: Int,
): ImageBitmap? {
    val decoded by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = photo.id,
        key2 = maxWidth,
        key3 = maxHeight,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching { File(photo.path).readBytes() }.getOrNull()?.let { bytes ->
                ImagePipeline.decodeImageBytes(bytes, maxWidth, maxHeight)?.asImageBitmap()
            }
        }
    }
    return decoded
}
