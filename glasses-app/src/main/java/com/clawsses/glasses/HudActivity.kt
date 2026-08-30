package com.clawsses.glasses

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.clawsses.glasses.camera.CameraCapture
import com.clawsses.glasses.camera.PhotoCaptureState
import com.clawsses.glasses.input.GestureHandler
import com.clawsses.glasses.media.ThumbnailBitmapCache
import com.clawsses.glasses.media.ThumbnailHandle
import com.clawsses.glasses.protocol.PhoneHudMessage
import com.clawsses.glasses.input.GestureHandler.Gesture
import com.clawsses.glasses.input.ModelPageSelection
import com.clawsses.glasses.orchestration.HudCatalogAction
import com.clawsses.glasses.orchestration.HudCatalogDecision
import com.clawsses.glasses.orchestration.HudCatalogInteractionController
import com.clawsses.glasses.orchestration.HudCommandDispatcher
import com.clawsses.glasses.orchestration.HudGestureContext
import com.clawsses.glasses.orchestration.HudGestureRouter
import com.clawsses.glasses.orchestration.HudGestureTarget
import com.clawsses.glasses.orchestration.HudInteractionAction
import com.clawsses.glasses.orchestration.HudInteractionDecision
import com.clawsses.glasses.orchestration.HudInteractionPlanner
import com.clawsses.glasses.orchestration.HudKeyRouter
import com.clawsses.glasses.orchestration.HudLifecycleRouter
import com.clawsses.glasses.orchestration.HudPhoneMessageController
import com.clawsses.glasses.orchestration.HudPhoneMessageEffect
import com.clawsses.glasses.orchestration.HudPhoneMessageEffectContext
import com.clawsses.glasses.orchestration.HudPhoneMessageEffectPlanner
import com.clawsses.glasses.orchestration.HudPhoneMessageResult
import com.clawsses.glasses.orchestration.HudRuntimeMetrics
import com.clawsses.glasses.orchestration.HudStreamController
import com.clawsses.glasses.service.PhoneConnectionService
import com.clawsses.glasses.state.HudStateEvent
import com.clawsses.glasses.state.HudHistorySnapshotAssembler
import com.clawsses.glasses.state.HudStateReducer
import com.clawsses.glasses.ui.AgentState
import com.clawsses.glasses.ui.AgentProgressDisplay
import com.clawsses.glasses.ui.AgentPickerInfo
import com.clawsses.glasses.ui.ChatFocusArea
import com.clawsses.glasses.ui.ChatHudState
import com.clawsses.glasses.ui.DisplayMessage
import com.clawsses.glasses.ui.HudDisplaySize
import com.clawsses.glasses.ui.HudCardActionDisplay
import com.clawsses.glasses.ui.HudCardDisplay
import com.clawsses.glasses.ui.HudPosition
import com.clawsses.glasses.ui.HudScreen
import com.clawsses.glasses.ui.toHudUiState
import com.clawsses.glasses.ui.HudContentNormalizer
import com.clawsses.glasses.ui.HudStreamingSnapshot
import com.clawsses.glasses.ui.HudTelemetry
import com.clawsses.glasses.ui.InputActionItem
import com.clawsses.glasses.ui.MenuBarItem
import com.clawsses.glasses.ui.MoreMenuItem
import com.clawsses.glasses.ui.MAX_PHOTOS
import com.clawsses.glasses.ui.SLASH_COMMANDS
import com.clawsses.glasses.ui.VoiceInputState
import com.clawsses.glasses.ui.RecognitionMode
import com.clawsses.glasses.ui.LiveCaptionDisplay
import com.clawsses.glasses.ui.visibleMoreMenuItems
import com.clawsses.glasses.ui.theme.GlassesHudTheme
import com.clawsses.glasses.voice.GlassesVoiceHandler
import com.clawsses.shared.GlassesCommand
import com.clawsses.shared.PeerProtocol
import com.clawsses.shared.VisionCommands
import com.clawsses.shared.TtsVoiceCommands
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import android.os.BatteryManager
import com.clawsses.glasses.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.clawsses.shared.TechnicalJankMonitor

class HudActivity : ComponentActivity() {
    private lateinit var jankMonitor: TechnicalJankMonitor

    companion object {
        val DEBUG_MODE = GlassesApp.DEBUG_MODE
        private const val STREAM_PUBLISH_INTERVAL_MS = 100L
        private const val ACTION_AI_START = "com.android.action.ACTION_AI_START"
        private const val ACTION_SPRITE_BUTTON_LONG_PRESS =
            "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"
        /** Sentinel key for the "New Session" entry in the session picker. */
        const val NEW_SESSION_KEY = "__new_session__"
        /** Sentinel key for loading the next bounded session page. */
        const val MORE_SESSIONS_KEY = "__more_sessions__"

    }

    private val hudState = MutableStateFlow(ChatHudState())
    private val hudTelemetry = MutableStateFlow(HudTelemetry())
    private val streamController = HudStreamController()
    private val streamingMessage = MutableStateFlow<HudStreamingSnapshot?>(null)
    private var streamPublishJob: Job? = null
    private lateinit var gestureHandler: GestureHandler
    private val phoneConnection: PhoneConnectionService
        get() = (application as GlassesApp).phoneConnection
    private val commandDispatcher by lazy {
        HudCommandDispatcher(phoneConnection::sendToPhone)
    }
    private lateinit var voiceHandler: GlassesVoiceHandler
    private lateinit var cameraCapture: CameraCapture
    private var aiStartReceiverRegistered = false
    private val aiStartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (
                action != ACTION_AI_START &&
                action != ACTION_SPRITE_BUTTON_LONG_PRESS
            ) return
            if (!::voiceHandler.isInitialized) return
            if (voiceHandler.isListening()) {
                Log.d(GlassesApp.TAG, "Ignoring duplicate firmware assistant broadcast: $action")
                return
            }
            Log.i(GlassesApp.TAG, "Firmware assistant broadcast received: $action")
            voiceHandler.startListening { /* result arrives through phone messages */ }
        }
    }

    // Thumbnails to attach to the next user message echo from the server

    // List updates also arrive during background state synchronization. These flags
    // ensure pickers open only after an explicit menu action.
    private var sessionPickerRequested = false
    private var sessionNextOffset: Int? = null
    private var agentPickerRequested = false
    private var modelPickerRequested = false
    private var pendingModelPageSelection = ModelPageSelection.CURRENT

    // History snapshots arrive as multiple CXR-safe commands. Keep assembly separate
    // from visible HUD state and swap it in only after the matching end marker arrives.
    private val historySnapshotAssembler = HudHistorySnapshotAssembler()
    private val runtimeMetrics = HudRuntimeMetrics()
    private val phoneMessageEffectPlanner = HudPhoneMessageEffectPlanner(
        newSessionKey = NEW_SESSION_KEY,
        moreSessionsKey = MORE_SESSIONS_KEY,
    )
    private val catalogInteractionController = HudCatalogInteractionController(
        newSessionKey = NEW_SESSION_KEY,
        moreSessionsKey = MORE_SESSIONS_KEY,
    )
    private val phoneMessageController by lazy {
        HudPhoneMessageController(
            metrics = runtimeMetrics,
            acknowledge = { transactionId ->
                sendCommand(GlassesCommand.TransportAck(transactionId))
            },
            deliver = ::handleTypedPhoneMessage,
        )
    }

    // Coroutine to clear newPrependCount after fade-in animations complete
    private var clearPrependJob: Job? = null

    // Debug keyboard input mode
    private var isCapturingKeyboardInput = false
    private var keyboardInputBuffer = StringBuilder()

    // Wake signal handling
    private var clearWakeNotificationJob: Job? = null
    private var cardExpiryJob: Job? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraCapture.capture()
        } else {
            Log.w(GlassesApp.TAG, "Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jankMonitor = TechnicalJankMonitor(window, GlassesApp.TAG, "hud")

        // Restore saved HUD preferences (font size, screen position)
        val (savedPosition, savedDisplaySize) = loadHudPreferences()
        hudState.value = hudState.value.copy(
            hudPosition = savedPosition,
            displaySize = savedDisplaySize
        )

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        gestureHandler = GestureHandler { gesture ->
            handleGesture(gesture)
        }

        Log.i(GlassesApp.TAG, "HudActivity attached to process-scoped phone bridge, debugMode=$DEBUG_MODE")

        voiceHandler = GlassesVoiceHandler()
        voiceHandler.sendCommand = ::sendCommand
        voiceHandler.initialize()
        val assistantIntentFilter = IntentFilter(ACTION_AI_START).apply {
            addAction(ACTION_SPRITE_BUTTON_LONG_PRESS)
        }
        ContextCompat.registerReceiver(
            this,
            aiStartReceiver,
            assistantIntentFilter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        aiStartReceiverRegistered = true

        cameraCapture = CameraCapture(this)

        // Observe voice state using atomic update to prevent race with stageVoiceText
        lifecycleScope.launch {
            voiceHandler.voiceState.collect { voiceState ->
                // Map GlassesVoiceHandler mode to UI mode
                fun mapMode(mode: GlassesVoiceHandler.RecognitionMode): RecognitionMode {
                    return when (mode) {
                        GlassesVoiceHandler.RecognitionMode.OPENAI -> RecognitionMode.OPENAI
                        GlassesVoiceHandler.RecognitionMode.DEVICE -> RecognitionMode.DEVICE
                    }
                }

                val newVoiceState = when (voiceState) {
                    is GlassesVoiceHandler.VoiceState.Idle -> VoiceInputState.Idle
                    is GlassesVoiceHandler.VoiceState.Listening -> VoiceInputState.Listening(mapMode(voiceState.mode))
                    is GlassesVoiceHandler.VoiceState.Recognizing -> VoiceInputState.Recognizing(mapMode(voiceState.mode))
                    is GlassesVoiceHandler.VoiceState.Processing -> VoiceInputState.Processing(mapMode(voiceState.mode))
                    is GlassesVoiceHandler.VoiceState.Error -> VoiceInputState.Error(voiceState.message)
                }
                val newVoiceText = when (voiceState) {
                    is GlassesVoiceHandler.VoiceState.Recognizing -> voiceState.partialText
                    else -> ""
                }
                // Use atomic update to avoid overwriting concurrent state changes
                // Don't force staging area open — the SDK AI scene shows recognized text.
                // Staging area opens when voice_result arrives (stageVoiceText).
                // If staging is already open (from previous input), the cursor animation
                // still shows via the Processing voice state in HudScreen.
                hudState.update { current ->
                    current.copy(
                        voiceState = newVoiceState,
                        voiceText = newVoiceText
                    )
                }
            }
        }

        // Observe camera capture state
        lifecycleScope.launch {
            cameraCapture.state.collect { photoState ->
                when (photoState) {
                    is PhotoCaptureState.Captured -> {
                        val current = hudState.value
                        if (current.photoThumbnails.size < MAX_PHOTOS) {
                            hudState.value = current.copy(
                                photoThumbnails = current.photoThumbnails +
                                    ThumbnailBitmapCache.put(photoState.thumbnail)
                            )
                        } else {
                            Log.w(GlassesApp.TAG, "Max $MAX_PHOTOS photos reached, ignoring capture")
                        }
                    }
                    is PhotoCaptureState.Error -> {
                        Log.e(GlassesApp.TAG, "Photo capture error")
                        lifecycleScope.launch {
                            delay(3000)
                            cameraCapture.clearPhoto()
                        }
                    }
                    is PhotoCaptureState.Idle -> { /* no-op for list-based photos */ }
                    is PhotoCaptureState.Capturing -> { /* capture in progress */ }
                }
            }
        }

        setContent {
            GlassesHudTheme {
                val state by hudState.collectAsStateWithLifecycle()
                val uiState = remember(state) { state.toHudUiState() }
                HudScreen(
                    state = uiState,
                    telemetry = hudTelemetry,
                    streamingMessage = streamingMessage,
                    onTap = { handleGesture(Gesture.TAP) },
                    onDoubleTap = { handleGesture(Gesture.DOUBLE_TAP) },
                    onLongPress = { handleGesture(Gesture.LONG_PRESS) },
                    onScrolledToEndChanged = { atEnd ->
                        hudState.update { current ->
                            if (current.isScrolledToEnd == atEnd) current
                            else current.copy(isScrolledToEnd = atEnd)
                        }
                    },
                    onPageStateChanged = { pageIndex, pageCount, _, atEnd ->
                        hudState.update { current ->
                            if (current.pageIndex == pageIndex &&
                                current.pageCount == pageCount &&
                                current.isScrolledToEnd == atEnd
                            ) {
                                current
                            } else {
                                Log.d(
                                    GlassesApp.TAG,
                                    "HUD page ${pageIndex + 1}/$pageCount atEnd=$atEnd",
                                )
                                current.copy(
                                    pageIndex = pageIndex,
                                    pageCount = pageCount,
                                    isScrolledToEnd = atEnd,
                                )
                            }
                        }
                    },
                )
            }
        }

        lifecycleScope.launch {
            phoneConnection.messages.collect(::handlePhoneMessage)
        }

        // Observe connection state and request current state when phone connects.
        // This fires on first connect AND on reconnect (e.g. after glasses app restart,
        // the phone auto-reconnects and the bridge fires onConnected or we detect
        // the connection via message receipt / ARTC status).
        lifecycleScope.launch {
            phoneConnection.connectionState.collect { state ->
                val isConnected = state is PhoneConnectionService.ConnectionState.Connected
                val current = hudState.value
                val transition = HudLifecycleRouter.connectionTransition(
                    currentlyConnected = current.isConnected,
                    bridgeConnected = isConnected,
                )
                if (transition.stateChanged) {
                    hudState.value = current.copy(isConnected = transition.connected)
                }
                if (transition.requestPhoneState) {
                    runtimeMetrics.recordReconnectStateRequest()
                    requestCurrentPhoneState()
                }
            }
        }

        // Poll battery level every 5 seconds (reads cached kernel value, negligible cost)
        lifecycleScope.launch {
            val batteryManager = getSystemService(BATTERY_SERVICE) as? BatteryManager
            while (true) {
                val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    ?.takeIf { it in 0..100 }
                val charging = batteryManager?.isCharging == true
                val current = hudTelemetry.value
                if (current.batteryLevel != level || current.batteryCharging != charging) {
                    hudTelemetry.value = current.copy(batteryLevel = level, batteryCharging = charging)
                }
                delay(5_000)
            }
        }

        // Update current time every minute (HH:MM, 24-hour format)
        lifecycleScope.launch {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            while (true) {
                val time = timeFormat.format(Date())
                val current = hudTelemetry.value
                if (current.currentTime != time) {
                    hudTelemetry.value = current.copy(currentTime = time)
                }
                // Calculate delay until next minute boundary for precise updates
                val now = System.currentTimeMillis()
                val delayMs = 60_000 - (now % 60_000)
                delay(delayMs)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        jankMonitor.onResume()
    }

    override fun onPause() {
        jankMonitor.onPause()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(GlassesApp.TAG, "HudActivity resumed through existing singleTask instance")
        requestCurrentPhoneState()
    }

    private fun requestCurrentPhoneState() {
        sendCommand(
            GlassesCommand.RequestState(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                protocolVersion = PeerProtocol.CURRENT_VERSION,
                capabilities = PeerProtocol.HUD_CAPABILITIES.toSet(),
            )
        )
    }

    private fun sendCommand(command: GlassesCommand) {
        runtimeMetrics.recordCommand()
        commandDispatcher.send(command)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { gestureHandler.onTouchEvent(it) }
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        event?.let {
            if (gestureHandler.onTouchEvent(it)) {
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If capturing keyboard input for simulated voice, handle specially
        if (isCapturingKeyboardInput) {
            return handleKeyboardCapture(keyCode, event)
        }

        val decision = HudKeyRouter.route(keyCode, event?.repeatCount ?: 0)
        decision.gesture?.let(::handleGesture)
        if (decision.consumed) return true

        if (DEBUG_MODE) {
            val char = event?.unicodeChar?.toChar()
            if (char != null && char.code > 0 && !char.isISOControl()) {
                startKeyboardCapture(char)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startKeyboardCapture(initialChar: Char? = null) {
        isCapturingKeyboardInput = true
        keyboardInputBuffer.clear()
        if (initialChar != null) {
            keyboardInputBuffer.append(initialChar)
        }
        hudState.value = hudState.value.copy(
            voiceState = if (initialChar != null) VoiceInputState.Recognizing() else VoiceInputState.Listening(),
            voiceText = initialChar?.toString() ?: ""
        )
    }

    private fun handleKeyboardCapture(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                val text = keyboardInputBuffer.toString().trim()
                isCapturingKeyboardInput = false
                keyboardInputBuffer.clear()
                if (text.isNotEmpty()) {
                    voiceHandler.simulateVoiceInput(text) { result ->
                        handleVoiceResult(result)
                    }
                } else {
                    hudState.value = hudState.value.copy(
                        voiceState = VoiceInputState.Idle,
                        voiceText = ""
                    )
                }
                return true
            }
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> {
                isCapturingKeyboardInput = false
                keyboardInputBuffer.clear()
                hudState.value = hudState.value.copy(
                    voiceState = VoiceInputState.Idle,
                    voiceText = ""
                )
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                if (keyboardInputBuffer.isNotEmpty()) {
                    keyboardInputBuffer.deleteCharAt(keyboardInputBuffer.length - 1)
                    updateKeyboardCaptureDisplay()
                }
                return true
            }
            KeyEvent.KEYCODE_SPACE -> {
                keyboardInputBuffer.append(' ')
                updateKeyboardCaptureDisplay()
                return true
            }
            else -> {
                val char = event?.unicodeChar?.toChar()
                if (char != null && char.code > 0 && !char.isISOControl()) {
                    keyboardInputBuffer.append(char)
                    updateKeyboardCaptureDisplay()
                    return true
                }
            }
        }
        return true
    }

    private fun updateKeyboardCaptureDisplay() {
        val text = keyboardInputBuffer.toString()
        hudState.value = hudState.value.copy(
            voiceState = if (text.isEmpty()) VoiceInputState.Listening() else VoiceInputState.Recognizing(),
            voiceText = text
        )
    }

    // ============== Simplified 3-Area Gesture Handling ==============

    private fun handleGesture(gesture: Gesture) {
        runtimeMetrics.recordGesture()
        val current = hudState.value
        val isVoiceActive = voiceHandler.isListening()

        Log.d(GlassesApp.TAG, "Gesture: $gesture, Area: ${current.focusedArea}")

        val target = HudGestureRouter.route(
            HudGestureContext(
                hasHudCards = current.hudCards.isNotEmpty(),
                liveCaptionEnabled = current.liveCaptionEnabled,
                showExitConfirm = current.showExitConfirm,
                showSlashMenu = current.showSlashMenu,
                showMoreMenu = current.showMoreMenu,
                showSessionPicker = current.showSessionPicker,
                showAgentPicker = current.showAgentPicker,
                showModelPicker = current.showModelPicker,
                voiceActive = isVoiceActive,
                focusedArea = current.focusedArea,
            ),
            gesture,
        )
        when (target) {
            HudGestureTarget.HUD_CARD -> handleHudCardGesture(gesture)
            HudGestureTarget.DISMISS_LIVE_CAPTION -> {
                sendCommand(GlassesCommand.LiveCaptionToggle(false))
                hudState.value = current.copy(liveCaptionEnabled = false, liveCaption = null)
            }
            HudGestureTarget.EXIT_CONFIRM -> handleExitConfirmGesture(gesture)
            HudGestureTarget.SLASH_MENU -> handleSlashMenuGesture(gesture)
            HudGestureTarget.MORE_MENU -> handleMoreMenuGesture(gesture)
            HudGestureTarget.SESSION_PICKER -> handleSessionPickerGesture(gesture)
            HudGestureTarget.AGENT_PICKER -> handleAgentPickerGesture(gesture)
            HudGestureTarget.MODEL_PICKER -> handleModelPickerGesture(gesture)
            HudGestureTarget.CANCEL_VOICE -> voiceHandler.cancel()
            HudGestureTarget.CONTENT,
            HudGestureTarget.INPUT,
            HudGestureTarget.MENU,
            -> applyInteractionDecision(HudInteractionPlanner.plan(current, target, gesture))
        }
    }

    private fun applyInteractionDecision(decision: HudInteractionDecision) {
        hudState.value = decision.state
        decision.actions.forEach { action ->
            when (action) {
                HudInteractionAction.ScrollUp -> scrollUp()
                HudInteractionAction.ScrollDown -> scrollDown()
                HudInteractionAction.ScrollToBottom -> scrollToBottom()
                HudInteractionAction.StartVoice -> startVoice()
                is HudInteractionAction.ExecuteMenuItem -> executeMenuItem(action.item)
                is HudInteractionAction.RemovePhoto -> sendCommand(
                    GlassesCommand.RemovePhoto(all = false, index = action.index),
                )
                HudInteractionAction.RemoveAllPhotos -> sendCommand(
                    GlassesCommand.RemovePhoto(all = true, index = null),
                )
                HudInteractionAction.SubmitInput -> submitInput()
            }
        }
    }

    // ============== Exit Confirmation Gestures ==============

    private fun handleExitConfirmGesture(gesture: Gesture) {
        val current = hudState.value
        when (gesture) {
            Gesture.DOUBLE_TAP -> {
                // Second double-tap confirms exit
                finishAffinity()
            }
            else -> {
                // Any other input dismisses the dialog
                hudState.value = current.copy(showExitConfirm = false)
            }
        }
    }

    // ============== Menu Item Actions ==============

    private fun executeMenuItem(item: MenuBarItem) {
        val current = hudState.value

        when (item) {
            MenuBarItem.PHOTO -> {
                requestPhotoCapture(sendAfterCapture = false)
            }
            MenuBarItem.SESSION -> {
                applyCatalogDecision(catalogInteractionController.requestSessions(current))
            }
            MenuBarItem.MODEL -> {
                applyCatalogDecision(catalogInteractionController.requestModels(current, -1))
            }
            MenuBarItem.SIZE -> {
                val nextPosition = when (current.hudPosition) {
                    HudPosition.FULL -> HudPosition.BOTTOM_HALF
                    HudPosition.BOTTOM_HALF -> HudPosition.TOP_HALF
                    HudPosition.TOP_HALF -> HudPosition.FULL
                }
                hudState.value = current.copy(
                    hudPosition = nextPosition,
                    scrollTrigger = current.scrollTrigger + 1
                )
            }
            MenuBarItem.MORE -> {
                hudState.value = current.copy(
                    showMoreMenu = true,
                    selectedMoreIndex = 0
                )
            }
        }
    }

    private fun requestPhotoCapture(sendAfterCapture: Boolean, visionPrompt: String? = null) {
        val current = hudState.value
        if (!sendAfterCapture && current.photoThumbnails.size >= MAX_PHOTOS) {
            Log.w(GlassesApp.TAG, "Max $MAX_PHOTOS photos reached, ignoring photo request")
            return
        }

        if (DEBUG_MODE) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraCapture.capture()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            return
        }

        sendCommand(GlassesCommand.TakePhoto(sendAfterCapture, visionPrompt))
        Log.d(GlassesApp.TAG, "Requested photo capture from phone (autoSend=$sendAfterCapture)")
    }

    // ============== Submit Input ==============

    private fun submitInput() {
        val current = hudState.value
        val text = current.inputText.trim()
        val thumbnails = current.photoThumbnails.toList()
        if (text.isEmpty() && thumbnails.isEmpty()) return
        Log.d(GlassesApp.TAG, "Submitting input (${text.length} chars, photos=${thumbnails.size}, focusArea=${current.focusedArea})")

        // Add user message to display immediately (optimistic update)
        val userMsg = DisplayMessage(
            id = "local-${System.currentTimeMillis()}",
            role = "user",
            content = text,
            isStreaming = false,
            thumbnails = thumbnails
        )
        val messages = current.messages.toMutableList()
        messages.add(userMsg)

        // Send user_input to phone
        sendCommand(GlassesCommand.UserInput(text, userMsg.id))

        // Tell phone to clear its photos too
        if (thumbnails.isNotEmpty()) {
            sendCommand(GlassesCommand.RemovePhoto(all = true, index = null))
        }
        hudState.value = current.copy(
            messages = messages,
            inputText = "",
            photoThumbnails = emptyList(),
            focusedArea = ChatFocusArea.CONTENT,
            scrollPosition = messages.size - 1,
            scrollTrigger = current.scrollTrigger + 1,
            pageNavigationDelta = 0,
            pageNavigationToLatest = true,
            pageNavigationHold = false,
            pageNavigationTrigger = current.pageNavigationTrigger + 1,
            showInputStaging = false,
            stagingText = "",
            inputActionIndex = 0
        )
    }

    // ============== Session Picker Gestures ==============

    private fun handleSessionPickerGesture(gesture: Gesture) {
        applyCatalogDecision(
            catalogInteractionController.planSessionGesture(
                state = hudState.value,
                gesture = gesture,
                nextOffset = sessionNextOffset,
            ),
        )
    }

    // ============== Model Picker Gestures ==============

    private fun handleModelPickerGesture(gesture: Gesture) {
        applyCatalogDecision(
            catalogInteractionController.planModelGesture(hudState.value, gesture),
        )
    }

    // ============== Agent Picker Gestures ==============

    private fun handleAgentPickerGesture(gesture: Gesture) {
        applyCatalogDecision(
            catalogInteractionController.planAgentGesture(hudState.value, gesture),
        )
    }

    // ============== More Menu Gestures ==============

    private fun handleMoreMenuGesture(gesture: Gesture) {
        val current = hudState.value
        val items = visibleMoreMenuItems(current.availableAgents.size > 1)
        val itemCount = items.size

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                val newIndex = if (current.selectedMoreIndex > 0) current.selectedMoreIndex - 1 else itemCount - 1
                hudState.value = current.copy(selectedMoreIndex = newIndex)
            }
            Gesture.SWIPE_BACKWARD -> {
                val newIndex = if (current.selectedMoreIndex < itemCount - 1) current.selectedMoreIndex + 1 else 0
                hudState.value = current.copy(selectedMoreIndex = newIndex)
            }
            Gesture.TAP -> {
                val selectedItem = items[current.selectedMoreIndex]
                // Close more menu first, then execute (which may open a submenu)
                hudState.value = current.copy(
                    showMoreMenu = false,
                    selectedMoreIndex = 0
                )
                executeMoreMenuItem(selectedItem)
            }
            Gesture.DOUBLE_TAP -> {
                hudState.value = current.copy(
                    showMoreMenu = false,
                    selectedMoreIndex = 0
                )
            }
            Gesture.LONG_PRESS -> {}
        }
    }

    private fun executeMoreMenuItem(item: MoreMenuItem) {
        val current = hudState.value

        if (item.displaySize != null) {
            hudState.value = current.copy(
                displaySize = item.displaySize,
                scrollTrigger = current.scrollTrigger + 1
            )
            return
        }

        when (item) {
            MoreMenuItem.AGENT -> applyCatalogDecision(
                catalogInteractionController.requestAgents(current),
            )
            MoreMenuItem.SLASH -> {
                hudState.value = current.copy(
                    showMoreMenu = false,
                    showSlashMenu = true,
                    selectedSlashIndex = 0
                )
            }
            MoreMenuItem.VOICE -> {
                // Toggle TTS and notify phone
                val newEnabled = !current.ttsEnabled
                hudState.value = current.copy(ttsEnabled = newEnabled)
                sendCommand(GlassesCommand.TtsToggle(newEnabled))
                Log.d(GlassesApp.TAG, "TTS toggle: $newEnabled")
            }
            MoreMenuItem.TALK -> {
                val enabled = !current.talkModeEnabled
                hudState.value = current.copy(
                    talkModeEnabled = enabled,
                    talkModePhase = if (enabled) "idle" else "off"
                )
                sendCommand(GlassesCommand.TalkModeToggle(enabled))
            }
            MoreMenuItem.CAPTIONS -> {
                val enabled = !current.liveCaptionEnabled
                hudState.value = current.copy(
                    liveCaptionEnabled = enabled,
                    liveCaption = if (enabled) LiveCaptionDisplay() else null,
                )
                sendCommand(GlassesCommand.LiveCaptionToggle(enabled))
            }
            MoreMenuItem.TTS_STOP -> {
                sendCommand(GlassesCommand.TtsControl("stop"))
            }
            MoreMenuItem.TTS_REPLAY -> {
                sendCommand(GlassesCommand.TtsControl("replay"))
            }
            MoreMenuItem.STOP_RUN -> {
                if (current.runCanAbort) {
                    sendCommand(GlassesCommand.AbortRun)
                    hudState.value = current.copy(
                        runState = "aborting",
                        runCanAbort = false,
                        agentState = AgentState.ABORTING
                    )
                }
            }
            else -> {}
        }
    }

    private fun appendToInput(char: String) {
        val current = hudState.value
        hudState.value = current.copy(inputText = current.inputText + char)
    }

    // ============== Slash Command Menu Gestures ==============

    private fun handleSlashMenuGesture(gesture: Gesture) {
        val current = hudState.value
        val commands = SLASH_COMMANDS

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                val newIndex = maxOf(0, current.selectedSlashIndex - 1)
                hudState.value = current.copy(selectedSlashIndex = newIndex)
            }
            Gesture.SWIPE_BACKWARD -> {
                val newIndex = minOf(commands.size - 1, current.selectedSlashIndex + 1)
                hudState.value = current.copy(selectedSlashIndex = newIndex)
            }
            Gesture.TAP -> {
                val item = commands[current.selectedSlashIndex]
                // Send slash command to phone
                sendCommand(GlassesCommand.Slash(item.command))
                hudState.value = current.copy(
                    showSlashMenu = false,
                    selectedSlashIndex = 0
                )
                Log.d(GlassesApp.TAG, "Slash command: ${item.command}")
            }
            Gesture.DOUBLE_TAP -> {
                hudState.value = current.copy(
                    showSlashMenu = false,
                    selectedSlashIndex = 0
                )
            }
            Gesture.LONG_PRESS -> {}
        }
    }

    // ============== Scroll Helpers ==============

    private fun scrollToBottom() {
        val current = hudState.value
        hudState.value = current.copy(
            pageNavigationDelta = 0,
            pageNavigationToLatest = true,
            pageNavigationHold = false,
            pageNavigationTrigger = current.pageNavigationTrigger + 1,
        )
    }

    private fun scrollUp() {
        val current = hudState.value
        if (current.pageIndex <= 0) {
            if (current.hasMoreHistory && !current.isLoadingMoreHistory && current.messages.isNotEmpty()) {
                hudState.value = current.copy(
                    pageNavigationDelta = 0,
                    pageNavigationToLatest = false,
                    pageNavigationHold = true,
                    pageNavigationTrigger = current.pageNavigationTrigger + 1,
                )
                requestMoreHistory()
            }
            return
        }
        hudState.value = current.copy(
            pageNavigationDelta = -1,
            pageNavigationToLatest = false,
            pageNavigationHold = false,
            pageNavigationTrigger = current.pageNavigationTrigger + 1,
        )
    }

    private fun requestMoreHistory() {
        val current = hudState.value
        if (current.isLoadingMoreHistory || !current.hasMoreHistory || current.messages.isEmpty()) return

        hudState.value = current.copy(isLoadingMoreHistory = true)

        sendCommand(GlassesCommand.RequestMoreHistory(current.messages.first().id))
        Log.d(GlassesApp.TAG, "Requesting more history (currentCount=${current.messages.size})")
    }

    private fun scrollDown() {
        val current = hudState.value
        if (current.pageIndex >= current.pageCount - 1) return
        hudState.value = current.copy(
            pageNavigationDelta = 1,
            pageNavigationToLatest = false,
            pageNavigationHold = false,
            pageNavigationTrigger = current.pageNavigationTrigger + 1,
        )
    }

    // ============== Voice Recognition ==============

    private fun startVoice() {
        if (voiceHandler.isListening()) {
            voiceHandler.cancel()
        } else {
            // Don't pass a result callback — voice_result messages from the phone
            // are handled directly in handlePhoneMessage to avoid the AI key path
            // issue where onResult is never set because startVoice() isn't called.
            voiceHandler.startListening { /* handled in handlePhoneMessage */ }
        }
    }

    /**
     * Append voice text to the staging area and show it.
     * Also clears voice UI state to prevent race condition with voice state collector.
     */
    private fun stageVoiceText(text: String) {
        Log.d(GlassesApp.TAG, "Staging voice text (${text.length} chars)")
        // Use atomic update to avoid race with concurrent state changes
        hudState.update { current ->
            val newStagingText = if (current.stagingText.isEmpty()) {
                text
            } else {
                "${current.stagingText} $text"
            }
            current.copy(
                stagingText = newStagingText,
                showInputStaging = true,
                focusedArea = ChatFocusArea.INPUT,
                inputActionIndex = current.photoThumbnails.size + 1,  // Default to Send
                scrollTrigger = current.scrollTrigger + 1,
                voiceState = VoiceInputState.Idle,
                voiceText = ""
            )
        }
    }

    private fun handleVoiceResult(result: GlassesVoiceHandler.VoiceResult) {
        // Called from onResult callback (start_voice path) and simulateVoiceInput (keyboard).
        // For phone-originated voice_result messages, staging is handled in handlePhoneMessage.
        when (result) {
            is GlassesVoiceHandler.VoiceResult.Text -> {
                val text = result.text.trim()
                if (text.isNotEmpty()) {
                    stageVoiceText(text)
                }
            }
            is GlassesVoiceHandler.VoiceResult.Command -> {
                handleVoiceCommand(result.command)
            }
            is GlassesVoiceHandler.VoiceResult.Error -> {
                Log.e(GlassesApp.TAG, "Voice command error")
                lifecycleScope.launch {
                    delay(3000)
                    val current = hudState.value
                    if (current.voiceState is VoiceInputState.Error) {
                        hudState.value = current.copy(
                            voiceState = VoiceInputState.Idle,
                            voiceText = ""
                        )
                    }
                }
            }
        }
    }

    private fun handleVoiceCommand(command: String) {
        VisionCommands.promptFor(command)?.let { prompt ->
            requestPhotoCapture(sendAfterCapture = true, visionPrompt = prompt)
            return
        }
        when (command) {
            TtsVoiceCommands.STOP_CURRENT_OUTPUT -> {
                sendCommand(GlassesCommand.TtsControl("stop"))
            }
            "scroll up" -> scrollUp()
            "scroll down" -> scrollDown()
            "take photo", "foto aufnehmen" -> requestPhotoCapture(sendAfterCapture = false)
            "take and send photo", "foto aufnehmen und senden" -> requestPhotoCapture(sendAfterCapture = true)
            "stop talk mode", "talk mode off", "talk modus stoppen", "talk modus aus" -> {
                // The phone owns Talk Mode and has already disabled it before forwarding this result.
            }
            "clear" -> {
                // Clear staging area if visible, otherwise clear inputText
                val current = hudState.value
                if (current.showInputStaging) {
                    hudState.value = current.copy(
                        showInputStaging = false,
                        stagingText = "",
                        inputActionIndex = 0,
                        focusedArea = ChatFocusArea.CONTENT
                    )
                } else {
                    hudState.value = current.copy(inputText = "")
                }
            }
            "send", "enter" -> {
                // Submit staging text if visible, otherwise submit inputText
                val current = hudState.value
                if (current.showInputStaging &&
                    (current.stagingText.isNotBlank() || current.photoThumbnails.isNotEmpty())) {
                    hudState.value = current.copy(inputText = current.stagingText.trim())
                    submitInput()
                    hudState.value = hudState.value.copy(
                        showInputStaging = false,
                        stagingText = "",
                        inputActionIndex = 0
                    )
                } else {
                    submitInput()
                }
            }
            else -> {
                // Treat as text input — stage it
                val text = command.trim()
                if (text.isNotEmpty()) {
                    stageVoiceText(text)
                }
            }
        }
    }

    private fun handleHudCardGesture(gesture: Gesture) {
        val current = hudState.value
        val card = current.hudCards.firstOrNull() ?: return
        val actions = card.actions.ifEmpty { listOf(HudCardActionDisplay("dismiss", "Dismiss")) }
        when (gesture) {
            Gesture.SWIPE_FORWARD -> hudState.value = current.copy(
                selectedHudCardActionIndex = if (current.selectedHudCardActionIndex > 0) {
                    current.selectedHudCardActionIndex - 1
                } else {
                    actions.lastIndex
                }
            )
            Gesture.SWIPE_BACKWARD -> hudState.value = current.copy(
                selectedHudCardActionIndex = if (current.selectedHudCardActionIndex < actions.lastIndex) {
                    current.selectedHudCardActionIndex + 1
                } else {
                    0
                }
            )
            Gesture.TAP -> dismissHudCard(card, actions[current.selectedHudCardActionIndex.coerceIn(actions.indices)])
            Gesture.DOUBLE_TAP -> dismissHudCard(card, HudCardActionDisplay("dismiss", "Dismiss"))
            Gesture.LONG_PRESS -> {}
        }
    }

    private fun dismissHudCard(card: HudCardDisplay, action: HudCardActionDisplay) {
        sendCommand(GlassesCommand.HudCardAction(card.id, action.id))
        hudState.update { current ->
            current.copy(
                hudCards = current.hudCards.filterNot { it.id == card.id },
                selectedHudCardActionIndex = 0,
            )
        }
        scheduleActiveCardExpiry()
    }

    private fun scheduleActiveCardExpiry() {
        cardExpiryJob?.cancel()
        val card = hudState.value.hudCards.firstOrNull() ?: return
        val expiresAt = card.expiresAt ?: return
        cardExpiryJob = lifecycleScope.launch {
            delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
            hudState.update { current -> current.copy(hudCards = current.hudCards.filterNot { it.id == card.id }) }
            scheduleActiveCardExpiry()
        }
    }

    // ============== Wake Signal Handling ==============
    // The Rokid micro-LED display is woken from the PHONE side via CXR-M SDK
    // (setGlassBrightness + setScreenOffTimeout). Android PowerManager on the
    // glasses does NOT control the hardware display. The glasses side only
    // handles the notification UI and sends wake_ack back to the phone.

    /**
     * Show a brief wake notification in the HUD to alert the user
     * that content is arriving. Auto-dismisses after 2 seconds.
     */
    private fun showWakeNotification(reason: String) {
        clearWakeNotificationJob?.cancel()

        hudState.update { current ->
            current.copy(
                showWakeNotification = true,
                wakeReason = reason
            )
        }

        // Auto-dismiss after 2 seconds
        clearWakeNotificationJob = lifecycleScope.launch {
            delay(2000)
            hudState.update { current ->
                current.copy(
                    showWakeNotification = false,
                    wakeReason = null
                )
            }
        }
    }

    // ============== Phone Communication ==============

    private fun applyCatalogDecision(decision: HudCatalogDecision) {
        if (decision.applyStateBeforeActions) hudState.value = decision.state
        decision.sessionRequestActive?.let { sessionPickerRequested = it }
        decision.modelRequestActive?.let { modelPickerRequested = it }
        decision.agentRequestActive?.let { agentPickerRequested = it }
        decision.actions.forEach { action ->
            when (action) {
                is HudCatalogAction.RequestSessions -> {
                    sessionNextOffset = null
                    sendCommand(GlassesCommand.ListSessions(action.offset))
                }
                HudCatalogAction.CreateSession -> sendCommand(GlassesCommand.CreateSession)
                is HudCatalogAction.SwitchSession -> sendCommand(
                    GlassesCommand.SwitchSession(action.sessionKey),
                )
                is HudCatalogAction.RequestModels -> {
                    pendingModelPageSelection = action.pageSelection
                    sendCommand(GlassesCommand.ListModels(action.offset))
                }
                is HudCatalogAction.SelectModel -> sendCommand(
                    GlassesCommand.SelectModel(
                        action.sessionKey,
                        action.catalogId,
                        action.modelIndex,
                    ),
                )
                HudCatalogAction.RequestAgents -> sendCommand(GlassesCommand.ListAgents)
                is HudCatalogAction.SwitchAgent -> sendCommand(
                    GlassesCommand.SwitchAgent(action.agentId, action.agentName),
                )
            }
        }
        if (!decision.applyStateBeforeActions) hudState.value = decision.state
    }

    // ============== Phone Message Handling ==============

    private fun handlePhoneMessage(json: String) {
        Log.d(GlassesApp.TAG, "Handling phone message (${json.length} chars)")
        when (val result = phoneMessageController.accept(json)) {
            HudPhoneMessageResult.Delivered -> Unit
            is HudPhoneMessageResult.Duplicate ->
                Log.d(GlassesApp.TAG, "Acknowledged duplicate transport transaction")
            is HudPhoneMessageResult.Malformed -> Log.w(
                GlassesApp.TAG,
                "Rejected malformed phone message type=${result.type}: ${result.reason}",
            )
            is HudPhoneMessageResult.Unknown ->
                Log.d(GlassesApp.TAG, "Unknown message type: ${result.type}")
            is HudPhoneMessageResult.HandlerFailed -> Log.e(
                GlassesApp.TAG,
                "Error applying phone message (${json.length} chars)",
                result.error,
            )
        }
    }

    private fun handleTypedPhoneMessage(message: PhoneHudMessage) {
        when (val effect = phoneMessageEffectPlanner.plan(
            current = hudState.value,
            message = message,
            context = HudPhoneMessageEffectContext(
                sessionPickerRequested = sessionPickerRequested,
                modelPickerRequested = modelPickerRequested,
                agentPickerRequested = agentPickerRequested,
                pendingModelPageSelection = pendingModelPageSelection,
            ),
        )) {
            is HudPhoneMessageEffect.Apply -> {
                applyPhoneMessageEffect(effect)
                return
            }
            HudPhoneMessageEffect.RuntimeOwned -> Unit
        }
        when (message) {
            is PhoneHudMessage.CompletedMessage -> {
                clearStreamingMessage(message.id)
                val current = hudState.value
                val displayMessage = message.toDisplayMessage()
                val reduction = HudStateReducer.reduce(
                    current,
                    HudStateEvent.MessageCompleted(displayMessage),
                )
                hudState.value = reduction.state
                if (message.role == "user" && reduction.state.messages === current.messages) {
                    Log.d(GlassesApp.TAG, "User echo already displayed; transport ACK retained")
                }
            }
            is PhoneHudMessage.History -> {
                clearStreamingMessage()
                applyTypedHistory(
                    message.messages.map { it.toDisplayMessage() },
                    message.isLoadMore,
                    message.hasMore,
                )
            }
            is PhoneHudMessage.HistoryBegin -> {
                clearStreamingMessage()
                historySnapshotAssembler.begin(
                    message.snapshotId,
                    message.isLoadMore,
                    message.hasMore,
                )
            }
            is PhoneHudMessage.HistoryChunk -> {
                if (!historySnapshotAssembler.append(
                        message.snapshotId,
                        message.id,
                        message.role,
                        message.content,
                    )
                ) {
                    Log.w(GlassesApp.TAG, "Ignored stale history chunk")
                }
            }
            is PhoneHudMessage.HistoryEnd -> {
                val snapshot = historySnapshotAssembler.finish(message.snapshotId)
                if (snapshot == null) {
                    Log.w(GlassesApp.TAG, "Ignored stale history end")
                } else {
                    val messages = snapshot.messages.map { assembled ->
                        DisplayMessage(
                            id = assembled.id,
                            role = assembled.role,
                            content = HudContentNormalizer.unwrapSoftLineBreaks(assembled.content),
                            isStreaming = false,
                        )
                    }
                    applyTypedHistory(messages, snapshot.isLoadMore, snapshot.hasMore)
                }
            }
            is PhoneHudMessage.Stream -> {
                val decision = streamController.acceptChunk(
                    state = hudState.value,
                    messageId = message.id,
                    chunk = message.chunk,
                    publicationPending = streamPublishJob != null,
                )
                hudState.value = decision.state
                if (decision.publishImmediately) {
                    publishStreamingMessage()
                } else if (decision.schedulePublication) {
                    streamPublishJob = lifecycleScope.launch {
                        delay(STREAM_PUBLISH_INTERVAL_MS)
                        publishStreamingMessage()
                        streamPublishJob = null
                    }
                }
            }
            is PhoneHudMessage.StreamEnd -> {
                streamPublishJob?.cancel()
                streamPublishJob = null
                val completedStream = streamController.finish(message.id)
                streamingMessage.value = null
                hudState.value = HudStateReducer.reduce(
                    hudState.value,
                    HudStateEvent.StreamCompleted(
                        message.id,
                        completedStream?.content?.let(HudContentNormalizer::unwrapSoftLineBreaks),
                    ),
                ).state
            }
            is PhoneHudMessage.VoiceState -> {
                voiceHandler.handleVoiceState(message.state, message.text, message.mode)
            }
            is PhoneHudMessage.VoiceResult -> {
                when (message.resultType) {
                    "text" -> message.text.trim().takeIf { it.isNotEmpty() && !message.autoSent }
                        ?.let(::stageVoiceText)
                    "command" -> handleVoiceCommand(message.text)
                }
                voiceHandler.handleVoiceResult(message.resultType, message.text)
            }
            is PhoneHudMessage.PhotoResult -> {
                if (message.status == "captured" && message.thumbnail != null) {
                    val current = hudState.value
                    if (current.photoThumbnails.size < MAX_PHOTOS) {
                        message.thumbnail.toThumbnailHandle()?.let { thumbnail ->
                            hudState.value = current.copy(
                                photoThumbnails = current.photoThumbnails + thumbnail,
                                focusedArea = ChatFocusArea.INPUT,
                                inputActionIndex = current.photoThumbnails.size + 2,
                            )
                        }
                    }
                }
            }
            is PhoneHudMessage.RemovePhoto -> {
                val current = hudState.value
                if (message.all) {
                    hudState.value = current.copy(photoThumbnails = emptyList())
                } else {
                    val index = message.index
                    if (index != null && index in current.photoThumbnails.indices) {
                        val updated = current.photoThumbnails.toMutableList().apply { removeAt(index) }
                        hudState.value = current.copy(
                            photoThumbnails = updated,
                            inputActionIndex = minOf(current.inputActionIndex, updated.size + 1),
                        )
                    }
                }
            }
            is PhoneHudMessage.WakeSignal -> {
                showWakeNotification(message.reason)
                sendCommand(
                    GlassesCommand.WakeAck(
                        ready = true,
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }
            else -> error("Deterministic phone message effect was not applied: ${message::class.simpleName}")
        }
    }

    private fun applyPhoneMessageEffect(effect: HudPhoneMessageEffect.Apply) {
        hudState.value = effect.state
        if (effect.sessionOffsetChanged) sessionNextOffset = effect.sessionNextOffset
        if (effect.sessionRequestCompleted) sessionPickerRequested = false
        if (effect.modelRequestCompleted) modelPickerRequested = false
        if (effect.agentRequestCompleted) agentPickerRequested = false
        if (effect.resetModelPageSelection) pendingModelPageSelection = ModelPageSelection.CURRENT
        if (effect.scheduleCardExpiry) scheduleActiveCardExpiry()
        effect.logMessage?.let { Log.i(GlassesApp.TAG, it) }
    }

    private fun applyTypedHistory(
        messages: List<DisplayMessage>,
        isLoadMore: Boolean,
        hasMore: Boolean,
    ) {
        val reduction = HudStateReducer.reduce(
            hudState.value,
            HudStateEvent.HistoryLoaded(messages, isLoadMore, hasMore),
        )
        hudState.value = reduction.state
        if (reduction.prependedCount > 0) {
            clearPrependJob?.cancel()
            clearPrependJob = lifecycleScope.launch {
                delay(5_000L)
                hudState.update { it.copy(newPrependCount = 0) }
            }
        }
    }

    private fun PhoneHudMessage.CompletedMessage.toDisplayMessage() = DisplayMessage(
        id = id,
        role = role,
        content = HudContentNormalizer.unwrapSoftLineBreaks(content),
        isStreaming = false,
        thumbnails = thumbnails.mapNotNull { thumbnail ->
            ThumbnailBitmapCache.decode(
                encoded = thumbnail.encoded,
                format = thumbnail.format,
                width = thumbnail.width,
                height = thumbnail.height,
            )
        },
    )

    private fun PhoneHudMessage.Thumbnail.toThumbnailHandle(): ThumbnailHandle? =
        ThumbnailBitmapCache.decode(
            encoded = encoded,
            format = format,
            width = width,
            height = height,
        )

    private fun publishStreamingMessage() {
        streamController.snapshotIfChanged()?.let {
            runtimeMetrics.recordStreamPublication()
            streamingMessage.value = it
        }
    }

    private fun clearStreamingMessage(id: String? = null) {
        streamController.clear(id)
        if (id == null || streamingMessage.value?.id == id) {
            streamPublishJob?.cancel()
            streamPublishJob = null
            streamingMessage.value = null
        }
    }

    override fun onDestroy() {
        if (aiStartReceiverRegistered) {
            unregisterReceiver(aiStartReceiver)
            aiStartReceiverRegistered = false
        }
        streamPublishJob?.cancel()
        Log.i(GlassesApp.TAG, runtimeMetrics.snapshot().toLogLine())
        jankMonitor.close()
        super.onDestroy()
        saveHudPreferences()
        cameraCapture.cleanup()
        voiceHandler.cleanup()
        clearWakeNotificationJob?.cancel()
        cardExpiryJob?.cancel()
    }

    private fun saveHudPreferences() {
        try {
            val state = hudState.value
            getSharedPreferences("hud_prefs", MODE_PRIVATE).edit()
                .putString("hudPosition", state.hudPosition.name)
                .putString("displaySize", state.displaySize.name)
                .apply()
        } catch (e: Exception) {
            Log.w(GlassesApp.TAG, "Failed to save HUD preferences", e)
        }
    }

    private fun loadHudPreferences(): Pair<HudPosition, HudDisplaySize> {
        try {
            val prefs = getSharedPreferences("hud_prefs", MODE_PRIVATE)
            val position = prefs.getString("hudPosition", null)
                ?.let { name -> HudPosition.entries.find { it.name == name } }
                ?: HudPosition.FULL
            val displaySize = prefs.getString("displaySize", null)
                ?.let { name -> HudDisplaySize.entries.find { it.name == name } }
                ?: HudDisplaySize.NORMAL
            return Pair(position, displaySize)
        } catch (e: Exception) {
            Log.w(GlassesApp.TAG, "Failed to load HUD preferences, using defaults", e)
            return Pair(HudPosition.FULL, HudDisplaySize.NORMAL)
        }
    }
}
