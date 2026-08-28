package com.clawsses.glasses

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import com.clawsses.glasses.input.GestureHandler.Gesture
import com.clawsses.glasses.input.ModelPageSelection
import com.clawsses.glasses.input.ModelPickerMove
import com.clawsses.glasses.input.ModelPickerNavigation
import com.clawsses.glasses.service.PhoneConnectionService
import com.clawsses.glasses.state.HudStateEvent
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
import com.clawsses.glasses.ui.HudStreamingAccumulator
import com.clawsses.glasses.ui.HudStreamingSnapshot
import com.clawsses.glasses.ui.HudTelemetry
import com.clawsses.glasses.ui.InputActionItem
import com.clawsses.glasses.ui.MenuBarItem
import com.clawsses.glasses.ui.ModelPickerInfo
import com.clawsses.glasses.ui.MoreMenuItem
import com.clawsses.glasses.ui.SessionPickerInfo
import com.clawsses.glasses.ui.MAX_PHOTOS
import com.clawsses.glasses.ui.SLASH_COMMANDS
import com.clawsses.glasses.ui.VoiceInputState
import com.clawsses.glasses.ui.RecognitionMode
import com.clawsses.glasses.ui.LiveCaptionDisplay
import com.clawsses.glasses.ui.visibleMoreMenuItems
import com.clawsses.glasses.ui.theme.GlassesHudTheme
import com.clawsses.glasses.voice.GlassesVoiceHandler
import com.clawsses.shared.GlassesStateRequest
import com.clawsses.shared.VisionCommands
import com.clawsses.shared.TtsVoiceCommands
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

import android.os.BatteryManager
import com.clawsses.glasses.BuildConfig
import java.text.SimpleDateFormat
import java.util.ArrayDeque
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
    private val streamingAccumulator = HudStreamingAccumulator()
    private val streamingMessage = MutableStateFlow<HudStreamingSnapshot?>(null)
    private var streamPublishJob: Job? = null
    private lateinit var gestureHandler: GestureHandler
    private val phoneConnection: PhoneConnectionService
        get() = (application as GlassesApp).phoneConnection
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
    private var pendingHistorySnapshotId: String? = null
    private var pendingHistoryHasMore = false
    private val pendingHistoryMessages = linkedMapOf<String, PendingHistoryMessage>()
    private val processedTransportTransactions = ArrayDeque<String>()

    // Coroutine to clear newPrependCount after fade-in animations complete
    private var clearPrependJob: Job? = null

    // Debug keyboard input mode
    private var isCapturingKeyboardInput = false
    private var keyboardInputBuffer = StringBuilder()

    // Wake signal handling
    private var clearWakeNotificationJob: Job? = null
    private var cardExpiryJob: Job? = null

    private data class PendingHistoryMessage(
        val role: String,
        val content: StringBuilder = StringBuilder(),
    )

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
        voiceHandler.sendToPhone = { message -> phoneConnection.sendToPhone(message) }
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
                                photoThumbnails = current.photoThumbnails + photoState.thumbnail
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
                HudScreen(
                    state = state,
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
                if (current.isConnected != isConnected) {
                    hudState.value = current.copy(isConnected = isConnected)
                    if (isConnected) {
                        requestCurrentPhoneState()
                    }
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
        phoneConnection.sendToPhone(
            GlassesStateRequest(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
            ).toJson()
        )
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

        if (event?.repeatCount ?: 0 > 0) return true

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> {
                handleGesture(Gesture.SWIPE_FORWARD)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleGesture(Gesture.SWIPE_BACKWARD)
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                handleGesture(Gesture.TAP)
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                handleGesture(Gesture.DOUBLE_TAP)
                return true
            }
            KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_DEL -> {
                handleGesture(Gesture.DOUBLE_TAP)
                return true
            }
            else -> {
                if (DEBUG_MODE) {
                    val char = event?.unicodeChar?.toChar()
                    if (char != null && char.code > 0 && !char.isISOControl()) {
                        startKeyboardCapture(char)
                        return true
                    }
                }
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
        val current = hudState.value
        val isVoiceActive = voiceHandler.isListening()

        Log.d(GlassesApp.TAG, "Gesture: $gesture, Area: ${current.focusedArea}")

        // If overlays are open, handle gestures for them
        if (current.hudCards.isNotEmpty()) {
            handleHudCardGesture(gesture)
            return
        }
        if (current.liveCaptionEnabled && gesture == Gesture.DOUBLE_TAP) {
            phoneConnection.sendToPhone("""{"type":"live_caption_toggle","enabled":false}""")
            hudState.value = current.copy(liveCaptionEnabled = false, liveCaption = null)
            return
        }
        if (current.showExitConfirm) {
            handleExitConfirmGesture(gesture)
            return
        }
        if (current.showSlashMenu) {
            handleSlashMenuGesture(gesture)
            return
        }
        if (current.showMoreMenu) {
            handleMoreMenuGesture(gesture)
            return
        }
        if (current.showSessionPicker) {
            handleSessionPickerGesture(gesture)
            return
        }
        if (current.showAgentPicker) {
            handleAgentPickerGesture(gesture)
            return
        }
        if (current.showModelPicker) {
            handleModelPickerGesture(gesture)
            return
        }

        // If voice is active, TAP cancels
        if (isVoiceActive && gesture == Gesture.TAP) {
            voiceHandler.cancel()
            return
        }

        // Route by focused area
        when (current.focusedArea) {
            ChatFocusArea.CONTENT -> handleContentGesture(gesture)
            ChatFocusArea.INPUT -> handleInputGesture(gesture)
            ChatFocusArea.MENU -> handleMenuGesture(gesture)
        }
    }

    // CONTENT area gestures
    private fun handleContentGesture(gesture: Gesture) {
        when (gesture) {
            Gesture.SWIPE_FORWARD -> scrollUp()
            Gesture.SWIPE_BACKWARD -> {
                val current = hudState.value
                if (current.pageIndex >= current.pageCount - 1 && current.isScrolledToEnd) {
                    // Push through: CONTENT → INPUT (if staging or photos) → MENU
                    if (current.showInputStaging || current.photoThumbnails.isNotEmpty()) {
                        // Default focus on last visible item in combined row
                        val lastIndex = current.photoThumbnails.size + 1 // Send button
                        hudState.value = current.copy(
                            focusedArea = ChatFocusArea.INPUT,
                            inputActionIndex = lastIndex
                        )
                    } else {
                        hudState.value = current.copy(
                            focusedArea = ChatFocusArea.MENU,
                            menuBarIndex = 0
                        )
                    }
                } else {
                    scrollDown()
                }
            }
            Gesture.TAP -> scrollToBottom()
            Gesture.DOUBLE_TAP -> {
                val current = hudState.value
                if (current.showInputStaging || current.photoThumbnails.isNotEmpty()) {
                    val lastIndex = current.photoThumbnails.size + 1 // Send button
                    hudState.value = current.copy(
                        focusedArea = ChatFocusArea.INPUT,
                        inputActionIndex = lastIndex
                    )
                } else {
                    hudState.value = current.copy(
                        focusedArea = ChatFocusArea.MENU,
                        menuBarIndex = 0
                    )
                }
            }
            Gesture.LONG_PRESS -> startVoice()
        }
    }

    // INPUT staging area gestures
    // Combined row: [photo0..N-1, CLEAR, SEND] for text, photos, or both.
    // inputActionIndex maps into this combined row.
    private fun handleInputGesture(gesture: Gesture) {
        val current = hudState.value
        val photoCount = current.photoThumbnails.size
        val clearIndex = photoCount       // CLEAR is right after photos
        val sendIndex = photoCount + 1    // SEND is rightmost
        val totalItems = photoCount + 2

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                if (current.inputActionIndex == 0) {
                    // Push through: INPUT → CONTENT
                    hudState.value = current.copy(focusedArea = ChatFocusArea.CONTENT)
                } else {
                    hudState.value = current.copy(inputActionIndex = current.inputActionIndex - 1)
                }
            }
            Gesture.SWIPE_BACKWARD -> {
                if (current.inputActionIndex >= totalItems - 1) {
                    // Push through: INPUT → MENU
                    hudState.value = current.copy(
                        focusedArea = ChatFocusArea.MENU,
                        menuBarIndex = 0
                    )
                } else {
                    hudState.value = current.copy(inputActionIndex = current.inputActionIndex + 1)
                }
            }
            Gesture.TAP -> {
                val idx = current.inputActionIndex
                when {
                    idx < photoCount -> {
                        // Tap on photo — remove it
                        val newThumbnails = current.photoThumbnails.toMutableList().apply { removeAt(idx) }
                        val newPhotoCount = newThumbnails.size
                        // After removal, keep focus on same position but clamp
                        val newIndex = if (newThumbnails.isEmpty() && !current.showInputStaging) {
                            // No photos left and no staging text — go to MENU
                            hudState.value = current.copy(
                                photoThumbnails = emptyList(),
                                inputActionIndex = 0,
                                focusedArea = ChatFocusArea.MENU,
                                menuBarIndex = 0
                            )
                            phoneConnection.sendToPhone("""{"type":"remove_photo","index":$idx}""")
                            return
                        } else {
                            // Keep the primary action focused after removing a photo.
                            newPhotoCount + 1
                        }
                        hudState.value = current.copy(
                            photoThumbnails = newThumbnails,
                            inputActionIndex = newIndex
                        )
                        phoneConnection.sendToPhone("""{"type":"remove_photo","index":$idx}""")
                    }
                    idx == clearIndex -> {
                        // Clear all staged content and dismiss.
                        hudState.value = current.copy(
                            showInputStaging = false,
                            stagingText = "",
                            photoThumbnails = emptyList(),
                            inputActionIndex = 0,
                            focusedArea = ChatFocusArea.CONTENT
                        )
                        if (current.photoThumbnails.isNotEmpty()) {
                            phoneConnection.sendToPhone("""{"type":"remove_photo","all":true}""")
                        }
                    }
                    idx == sendIndex -> {
                        // Submit staged text and/or photos, then dismiss.
                        val text = current.stagingText.trim()
                        if (text.isNotEmpty() || current.photoThumbnails.isNotEmpty()) {
                            hudState.value = current.copy(inputText = text)
                            submitInput()
                        }
                        hudState.value = hudState.value.copy(
                            showInputStaging = false,
                            stagingText = "",
                            inputActionIndex = 0
                        )
                    }
                }
            }
            Gesture.DOUBLE_TAP -> {
                // Go back to CONTENT
                hudState.value = current.copy(focusedArea = ChatFocusArea.CONTENT)
            }
            Gesture.LONG_PRESS -> startVoice()
        }
    }

    // MENU area gestures
    private fun handleMenuGesture(gesture: Gesture) {
        val current = hudState.value
        val items = MenuBarItem.entries

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                if (current.menuBarIndex == 0) {
                    // Push through: MENU → INPUT (if staging or photos) → CONTENT
                    if (current.showInputStaging || current.photoThumbnails.isNotEmpty()) {
                        // Focus on last visible item in combined row
                        val lastIndex = current.photoThumbnails.size + 1 // Send button
                        hudState.value = current.copy(
                            focusedArea = ChatFocusArea.INPUT,
                            inputActionIndex = lastIndex
                        )
                    } else {
                        hudState.value = current.copy(focusedArea = ChatFocusArea.CONTENT)
                    }
                } else {
                    hudState.value = current.copy(menuBarIndex = current.menuBarIndex - 1)
                }
            }
            Gesture.SWIPE_BACKWARD -> {
                // Next menu item
                val newIndex = minOf(items.size - 1, current.menuBarIndex + 1)
                hudState.value = current.copy(menuBarIndex = newIndex)
            }
            Gesture.TAP -> {
                // Execute selected menu item
                executeMenuItem(items[current.menuBarIndex])
            }
            Gesture.DOUBLE_TAP -> {
                // Show exit confirmation dialog
                hudState.value = current.copy(showExitConfirm = true)
            }
            Gesture.LONG_PRESS -> startVoice()
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
                requestSessionList()
            }
            MenuBarItem.MODEL -> {
                requestModelPage(-1)
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

        val request = JSONObject().apply {
            put("type", "take_photo")
            put("sendAfterCapture", sendAfterCapture)
            visionPrompt?.let { put("visionPrompt", it) }
        }
        phoneConnection.sendToPhone(request.toString())
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
        val json = JSONObject().apply {
            put("type", "user_input")
            put("text", text)
        }
        phoneConnection.sendToPhone(json.toString())

        // Tell phone to clear its photos too
        if (thumbnails.isNotEmpty()) {
            phoneConnection.sendToPhone("""{"type":"remove_photo","all":true}""")
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
        val current = hudState.value
        val totalOptions = current.availableSessions.size

        if (current.isSessionOperationPending) {
            when (gesture) {
                Gesture.TAP -> {
                    val selected = current.availableSessions.getOrNull(current.selectedSessionIndex)
                    if (selected?.key == NEW_SESSION_KEY) {
                        sessionPickerRequested = false
                        createNewSession()
                        hudState.value = current.copy(
                            showSessionPicker = true,
                            currentSessionName = null,
                            isSessionOperationPending = true,
                            sessionOperationMessage = "Creating session...",
                            sessionOperationError = null
                        )
                    }
                }
                Gesture.DOUBLE_TAP -> {
                    sessionPickerRequested = false
                    hudState.value = current.copy(
                        showSessionPicker = false,
                        isSessionOperationPending = false,
                        sessionOperationMessage = null,
                        sessionOperationError = null
                    )
                }
                else -> Unit
            }
            return
        }

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                val newIndex = maxOf(0, current.selectedSessionIndex - 1)
                hudState.value = current.copy(selectedSessionIndex = newIndex)
            }
            Gesture.SWIPE_BACKWARD -> {
                if (totalOptions > 0) {
                    val newIndex = minOf(totalOptions - 1, current.selectedSessionIndex + 1)
                    hudState.value = current.copy(selectedSessionIndex = newIndex)
                }
            }
            Gesture.TAP -> {
                if (totalOptions > 0) {
                    val selected = current.availableSessions[current.selectedSessionIndex]
                    if (selected.key == NEW_SESSION_KEY) {
                        sessionPickerRequested = false
                        createNewSession()
                        hudState.value = current.copy(
                            showSessionPicker = true,
                            currentSessionName = null,
                            isSessionOperationPending = true,
                            sessionOperationMessage = "Creating session...",
                            sessionOperationError = null
                        )
                    } else if (selected.key == MORE_SESSIONS_KEY) {
                        requestSessionList(sessionNextOffset ?: 0)
                    } else {
                        switchToSession(selected.key)
                        hudState.value = current.copy(
                            showSessionPicker = false,
                            currentSessionName = selected.name,
                            sessionOperationError = null
                        )
                    }
                } else {
                    sessionPickerRequested = false
                    hudState.value = current.copy(showSessionPicker = false)
                }
            }
            Gesture.DOUBLE_TAP -> {
                sessionPickerRequested = false
                hudState.value = current.copy(showSessionPicker = false)
            }
            Gesture.LONG_PRESS -> {}
        }
    }

    private fun createNewSession() {
        phoneConnection.sendToPhone("""{"type":"create_session"}""")
    }

    // ============== Model Picker Gestures ==============

    private fun handleModelPickerGesture(gesture: Gesture) {
        val current = hudState.value
        if (current.isModelOperationPending) {
            if (gesture == Gesture.DOUBLE_TAP) {
                modelPickerRequested = false
                hudState.value = current.copy(
                    showModelPicker = false,
                    isModelOperationPending = false,
                    modelOperationMessage = null,
                )
            }
            return
        }

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                applyModelPickerMove(
                    ModelPickerNavigation.forward(
                        selectedIndex = current.selectedModelIndex,
                        itemCount = current.availableModels.size,
                        nextOffset = current.modelNextOffset,
                    )
                )
            }
            Gesture.SWIPE_BACKWARD -> {
                applyModelPickerMove(
                    ModelPickerNavigation.backward(
                        selectedIndex = current.selectedModelIndex,
                        itemCount = current.availableModels.size,
                        pageOffset = current.modelPageOffset,
                    )
                )
            }
            Gesture.TAP -> {
                val selected = current.availableModels.getOrNull(current.selectedModelIndex)
                when {
                    selected == null -> Unit
                    !selected.available -> hudState.value = current.copy(
                        modelOperationError = "Model is unavailable",
                    )
                    selected.index == current.currentModelIndex -> {
                        modelPickerRequested = false
                        hudState.value = current.copy(showModelPicker = false)
                    }
                    current.runState !in setOf("idle", "error") -> hudState.value = current.copy(
                        modelOperationError = "Available after response",
                    )
                    else -> selectModel(selected)
                }
            }
            Gesture.DOUBLE_TAP -> {
                modelPickerRequested = false
                hudState.value = current.copy(showModelPicker = false)
            }
            Gesture.LONG_PRESS -> Unit
        }
    }

    private fun applyModelPickerMove(move: ModelPickerMove) {
        move.selectedIndex?.let { selectedIndex ->
            hudState.update { current -> current.copy(selectedModelIndex = selectedIndex) }
        }
        move.requestedOffset?.let { offset ->
            requestModelPage(offset, move.pageSelection)
        }
    }

    // ============== Agent Picker Gestures ==============

    private fun handleAgentPickerGesture(gesture: Gesture) {
        val current = hudState.value
        val totalOptions = current.availableAgents.size

        when (gesture) {
            Gesture.SWIPE_FORWARD -> {
                if (totalOptions > 0) {
                    hudState.value = current.copy(
                        selectedAgentIndex = maxOf(0, current.selectedAgentIndex - 1)
                    )
                }
            }
            Gesture.SWIPE_BACKWARD -> {
                if (totalOptions > 0) {
                    hudState.value = current.copy(
                        selectedAgentIndex = minOf(totalOptions - 1, current.selectedAgentIndex + 1)
                    )
                }
            }
            Gesture.TAP -> {
                val selected = current.availableAgents.getOrNull(current.selectedAgentIndex)
                if (selected != null) {
                    switchToAgent(selected.id, selected.name)
                    hudState.value = current.copy(
                        showAgentPicker = false,
                        currentAgentId = selected.id,
                        currentAgentName = selected.name,
                        currentSessionName = selected.name
                    )
                } else {
                    hudState.value = current.copy(showAgentPicker = false)
                }
            }
            Gesture.DOUBLE_TAP -> hudState.value = current.copy(showAgentPicker = false)
            Gesture.LONG_PRESS -> {}
        }
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
            MoreMenuItem.AGENT -> requestAgentList()
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
                val json = JSONObject().apply {
                    put("type", "tts_toggle")
                    put("enabled", newEnabled)
                }
                phoneConnection.sendToPhone(json.toString())
                Log.d(GlassesApp.TAG, "TTS toggle: $newEnabled")
            }
            MoreMenuItem.TALK -> {
                val enabled = !current.talkModeEnabled
                hudState.value = current.copy(
                    talkModeEnabled = enabled,
                    talkModePhase = if (enabled) "idle" else "off"
                )
                phoneConnection.sendToPhone(
                    org.json.JSONObject().apply {
                        put("type", "talk_mode_toggle")
                        put("enabled", enabled)
                    }.toString()
                )
            }
            MoreMenuItem.CAPTIONS -> {
                val enabled = !current.liveCaptionEnabled
                hudState.value = current.copy(
                    liveCaptionEnabled = enabled,
                    liveCaption = if (enabled) LiveCaptionDisplay() else null,
                )
                phoneConnection.sendToPhone(
                    JSONObject().apply {
                        put("type", "live_caption_toggle")
                        put("enabled", enabled)
                    }.toString()
                )
            }
            MoreMenuItem.TTS_STOP -> {
                phoneConnection.sendToPhone("""{"type":"tts_control","action":"stop"}""")
            }
            MoreMenuItem.TTS_REPLAY -> {
                phoneConnection.sendToPhone("""{"type":"tts_control","action":"replay"}""")
            }
            MoreMenuItem.STOP_RUN -> {
                if (current.runCanAbort) {
                    phoneConnection.sendToPhone("""{"type":"abort_run"}""")
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
                val json = JSONObject().apply {
                    put("type", "slash_command")
                    put("command", item.command)
                }
                phoneConnection.sendToPhone(json.toString())
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

        val json = JSONObject().apply {
            put("type", "request_more_history")
            put("currentCount", current.messages.size)
        }
        phoneConnection.sendToPhone(json.toString())
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
                phoneConnection.sendToPhone("""{"type":"tts_control","action":"stop"}""")
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
        phoneConnection.sendToPhone(
            JSONObject().apply {
                put("type", "hud_card_action")
                put("cardId", card.id)
                put("actionId", action.id)
            }.toString()
        )
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

    private fun requestSessionList(offset: Int = 0) {
        sessionPickerRequested = true
        sessionNextOffset = null
        hudState.update { current ->
            val options = listOf(SessionPickerInfo(NEW_SESSION_KEY, "+ New Session"))
            current.copy(
                showSessionPicker = true,
                availableSessions = options,
                selectedSessionIndex = 0,
                isSessionOperationPending = true,
                sessionOperationMessage = "Loading sessions...",
                sessionOperationError = null
            )
        }
        val request = JSONObject().apply {
            put("type", "list_sessions")
            put("offset", offset.coerceAtLeast(0))
        }
        phoneConnection.sendToPhone(request.toString())
    }

    private fun switchToSession(sessionKey: String) {
        val json = JSONObject().apply {
            put("type", "switch_session")
            put("sessionKey", sessionKey)
        }
        phoneConnection.sendToPhone(json.toString())
    }

    private fun requestModelPage(
        offset: Int,
        pageSelection: ModelPageSelection = ModelPageSelection.CURRENT,
    ) {
        modelPickerRequested = true
        pendingModelPageSelection = pageSelection
        hudState.update { current ->
            current.copy(
                showModelPicker = true,
                isModelOperationPending = true,
                modelOperationMessage = "Loading models...",
                modelOperationError = null,
            )
        }
        phoneConnection.sendToPhone(
            JSONObject().apply {
                put("type", "list_models")
                put("offset", offset)
            }.toString()
        )
    }

    private fun selectModel(model: ModelPickerInfo) {
        val current = hudState.value
        val catalogId = current.modelCatalogId ?: return
        val sessionKey = current.currentSessionKey ?: return
        hudState.value = current.copy(
            isModelOperationPending = true,
            modelOperationMessage = "Changing model...",
            modelOperationError = null,
        )
        phoneConnection.sendToPhone(
            JSONObject().apply {
                put("type", "select_model")
                put("catalog", catalogId)
                put("index", model.index)
                put("sessionKey", sessionKey)
            }.toString()
        )
    }

    private fun requestAgentList() {
        agentPickerRequested = true
        phoneConnection.sendToPhone("""{"type":"list_agents"}""")
    }

    private fun switchToAgent(agentId: String, agentName: String) {
        val json = JSONObject().apply {
            put("type", "switch_agent")
            put("agentId", agentId)
            put("agentName", agentName)
        }
        phoneConnection.sendToPhone(json.toString())
    }

    private fun agentIdFromSessionKey(key: String?): String? {
        if (key.isNullOrBlank()) return null
        val parts = key.split(':')
        return parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "agent" && it.isNotBlank() }
    }

    // ============== Phone Message Handling ==============

    private fun handlePhoneMessage(json: String) {
        try {
            Log.d(GlassesApp.TAG, "Handling phone message (${json.length} chars)")
            val msg = JSONObject(json)
            val type = msg.optString("type", "")
            val transactionId = msg.optString("_tx").takeIf { it.isNotBlank() }
            if (transactionId != null && processedTransportTransactions.contains(transactionId)) {
                phoneConnection.sendToPhone("""{"type":"transport_ack","tx":"$transactionId"}""")
                return
            }

            when (type) {
                "chat_message" -> {
                    // Complete message (user echo or finished assistant message)
                    val id = msg.optString("id", "")
                    val role = msg.optString("role", "assistant")
                    val content = unwrapContent(msg.optString("content", ""))
                    val incomingThumbnails = parseAttachmentThumbnails(msg)
                    clearStreamingMessage(id)

                    val current = hudState.value
                    val displayMessage = DisplayMessage(
                        id = id,
                        role = role,
                        content = content,
                        isStreaming = false,
                        thumbnails = incomingThumbnails,
                    )
                    val reduction = HudStateReducer.reduce(
                        current,
                        HudStateEvent.MessageCompleted(displayMessage),
                    )
                    hudState.value = reduction.state
                    val duplicateUserEcho = role == "user" && reduction.state.messages === current.messages
                    if (duplicateUserEcho) {
                        Log.d(GlassesApp.TAG, "User echo already displayed; state unchanged, transport ACK retained")
                    } else {
                        Log.d(
                            GlassesApp.TAG,
                            "$role message received (${content.length} chars, photos=${displayMessage.thumbnails.size})",
                        )
                    }
                }

                "chat_history" -> {
                    clearStreamingMessage()
                    // Parse message list
                    val messagesArray = msg.optJSONArray("messages")
                    val isLoadMore = msg.optBoolean("isLoadMore", false)
                    val hasMore = msg.optBoolean("hasMore", true)
                    val messages = mutableListOf<DisplayMessage>()

                    if (messagesArray != null) {
                        for (i in 0 until messagesArray.length()) {
                            val msgObj = messagesArray.optJSONObject(i) ?: continue
                            val id = msgObj.optString("i", msgObj.optString("id", ""))
                            val compactRole = msgObj.optString("r", "")
                            val role = when (compactRole) {
                                "u" -> "user"
                                "a" -> "assistant"
                                else -> msgObj.optString("role", "assistant")
                            }
                            val content = unwrapContent(msgObj.optString("c", msgObj.optString("content", "")))
                            val thumbnails = parseAttachmentThumbnails(msgObj)
                            messages.add(DisplayMessage(
                                id = id,
                                role = role,
                                content = content,
                                isStreaming = false,
                                thumbnails = thumbnails,
                            ))
                        }
                    }

                    val reduction = HudStateReducer.reduce(
                        hudState.value,
                        HudStateEvent.HistoryLoaded(messages, isLoadMore, hasMore),
                    )
                    hudState.value = reduction.state
                    if (reduction.prependedCount > 0) {
                        clearPrependJob?.cancel()
                        clearPrependJob = lifecycleScope.launch {
                            delay(5000)
                            hudState.update { it.copy(newPrependCount = 0) }
                        }
                        Log.d(
                            GlassesApp.TAG,
                            "Load-more: prepended ${reduction.prependedCount} messages " +
                                "(total=${messages.size}, hasMore=$hasMore)",
                        )
                    } else if (isLoadMore) {
                        Log.d(GlassesApp.TAG, "No more history available")
                    } else {
                        Log.d(GlassesApp.TAG, "Loaded ${messages.size} history messages")
                    }
                }

                "chat_history_begin" -> {
                    clearStreamingMessage()
                    pendingHistorySnapshotId = msg.optString("s")
                    pendingHistoryHasMore = msg.optBoolean("hasMore", false)
                    pendingHistoryMessages.clear()
                    Log.d(GlassesApp.TAG, "History snapshot started")
                }

                "chat_history_chunk" -> {
                    val snapshotId = msg.optString("s")
                    if (snapshotId != pendingHistorySnapshotId) {
                        Log.w(GlassesApp.TAG, "Ignored stale history chunk")
                    } else {
                        val id = msg.optString("i")
                        val role = when (msg.optString("r")) {
                            "u" -> "user"
                            "a" -> "assistant"
                            else -> "assistant"
                        }
                        pendingHistoryMessages
                            .getOrPut(id) { PendingHistoryMessage(role) }
                            .content
                            .append(msg.optString("c"))
                    }
                }

                "chat_history_end" -> {
                    val snapshotId = msg.optString("s")
                    if (snapshotId != pendingHistorySnapshotId) {
                        Log.w(GlassesApp.TAG, "Ignored stale history end")
                    } else {
                        val messages = pendingHistoryMessages.map { (id, pending) ->
                            DisplayMessage(
                                id = id,
                                role = pending.role,
                                content = unwrapContent(pending.content.toString()),
                                isStreaming = false,
                            )
                        }
                        hudState.value = HudStateReducer.reduce(
                            hudState.value,
                            HudStateEvent.HistoryLoaded(
                                messages = messages,
                                isLoadMore = false,
                                hasMore = pendingHistoryHasMore,
                            ),
                        ).state
                        pendingHistorySnapshotId = null
                        pendingHistoryMessages.clear()
                        Log.d(GlassesApp.TAG, "Loaded complete chunked history (${messages.size} messages)")
                    }
                }

                "agent_thinking" -> {
                    val phase = msg.optString("phase", "thinking")
                    hudState.value = HudStateReducer.reduce(
                        hudState.value,
                        HudStateEvent.AgentPhaseChanged(phase),
                    ).state
                    Log.d(GlassesApp.TAG, "Agent phase: $phase")
                }

                "agent_progress" -> {
                    val state = msg.optString("state", "active")
                    hudState.value = HudStateReducer.reduce(
                        hudState.value,
                        HudStateEvent.AgentProgressChanged(
                            id = msg.optString("id", ""),
                            kind = msg.optString("kind", "status"),
                            label = msg.optString("label", ""),
                            state = state,
                        ),
                    ).state
                    Log.d(GlassesApp.TAG, "Agent progress state: $state")
                }

                "chat_stream" -> {
                    val id = msg.optString("id", "")
                    val chunk = msg.optString("chunk", "")
                    val current = hudState.value
                    val startedNewMessage = streamingAccumulator.append(id, chunk)
                    if (startedNewMessage || current.agentState != AgentState.STREAMING) {
                        hudState.value = current.copy(
                            agentState = AgentState.STREAMING,
                            agentProgress = emptyList(),
                        )
                        publishStreamingMessage()
                    } else if (streamPublishJob == null && streamingAccumulator.hasUnpublishedChanges()) {
                        streamPublishJob = lifecycleScope.launch {
                            delay(STREAM_PUBLISH_INTERVAL_MS)
                            publishStreamingMessage()
                            streamPublishJob = null
                        }
                    }
                }

                "chat_stream_end" -> {
                    val id = msg.optString("id", "")
                    streamPublishJob?.cancel()
                    streamPublishJob = null
                    val completedStream = streamingAccumulator.finish(id)
                    streamingMessage.value = null
                    hudState.value = HudStateReducer.reduce(
                        hudState.value,
                        HudStateEvent.StreamCompleted(
                            id = id,
                            content = completedStream?.content?.let(::unwrapContent),
                        ),
                    ).state

                    Log.d(GlassesApp.TAG, "Stream ended for $id")
                }

                "connection_update" -> {
                    val connected = msg.optBoolean("connected", false)
                    val sessionKey = msg.optString("sessionId", "")
                    val sessionName = msg.optString("sessionName", "")

                    val previousSessionKey = hudState.value.currentSessionKey
                    hudState.value = HudStateReducer.reduce(
                        hudState.value,
                        HudStateEvent.ConnectionChanged(
                            connected = connected,
                            sessionKey = sessionKey.takeIf { it.isNotEmpty() },
                            sessionName = sessionName.takeIf { it.isNotEmpty() },
                        ),
                    ).state

                    Log.d(
                        GlassesApp.TAG,
                        "Connection update: connected=$connected, " +
                            "sessionChanged=${hudState.value.currentSessionKey != previousSessionKey}",
                    )
                }

                "session_list" -> {
                    // Session list from phone
                    val sessionsArray = msg.optJSONArray("sessions")
                    val currentSessionKey = msg.optString("currentSessionKey", "")
                    val unreadArray = msg.optJSONArray("unreadSessionKeys")
                    val unreadKeys = mutableSetOf<String>()
                    if (unreadArray != null) {
                        for (i in 0 until unreadArray.length()) {
                            unreadKeys.add(unreadArray.optString(i, ""))
                        }
                    }
                    val sessions = mutableListOf<SessionPickerInfo>()

                    if (sessionsArray != null) {
                        for (i in 0 until sessionsArray.length()) {
                            val sessionObj = sessionsArray.optJSONObject(i)
                            if (sessionObj != null) {
                                val key = sessionObj.optString("k", sessionObj.optString("key", ""))
                                if (key.isBlank()) continue
                                val compactName = sessionObj.optString("n", "")
                                val label = sessionObj.optString("label", "")
                                val displayName = sessionObj.optString("displayName", "")
                                val derivedTitle = sessionObj.optString("derivedTitle", "")
                                val kind = sessionObj.optString("kind", "")
                                val updatedAt = if (sessionObj.has("updatedAt")) sessionObj.optLong("updatedAt", 0L).takeIf { it > 0 } else null
                                // Use best available name: label > displayName > derivedTitle > key
                                val name = compactName.ifEmpty {
                                    label.ifEmpty { displayName.ifEmpty { derivedTitle.ifEmpty { key } } }
                                }
                                sessions.add(SessionPickerInfo(
                                    key = key,
                                    name = name,
                                    kind = kind.ifEmpty { null },
                                    hasUnread = sessionObj.optBoolean("u", key in unreadKeys),
                                    updatedAt = updatedAt
                                ))
                            }
                        }
                    }

                    // Insert "New Session" as the first option
                    val hasMore = msg.optBoolean("hasMore", false)
                    sessionNextOffset = msg.optInt("nextOffset", -1).takeIf { hasMore && it >= 0 }
                    val sessionsWithNew = listOf(
                        SessionPickerInfo(
                            key = NEW_SESSION_KEY,
                            name = "+ New Session"
                        )
                    ) + sessions + if (sessionNextOffset != null) {
                        listOf(SessionPickerInfo(MORE_SESSIONS_KEY, "More..."))
                    } else {
                        emptyList()
                    }

                    hudState.value = HudStateReducer.reduce(
                        hudState.value,
                        HudStateEvent.SessionsLoaded(
                            sessions = sessionsWithNew,
                            currentSessionKey = currentSessionKey.takeIf { it.isNotEmpty() },
                        ),
                    ).state
                    sessionPickerRequested = false

                    Log.d(GlassesApp.TAG, "Session list received (${sessions.size} entries)")
                }

                "session_operation" -> {
                    val operation = msg.optString("operation")
                    val state = msg.optString("state")
                    val error = msg.optString("error").takeIf { it.isNotBlank() }
                    hudState.update { current ->
                        when (state) {
                            "loading" -> current.copy(
                                showSessionPicker = true,
                                isSessionOperationPending = true,
                                sessionOperationMessage = if (operation == "create") {
                                    "Creating session..."
                                } else {
                                    "Loading sessions..."
                                },
                                sessionOperationError = null
                            )
                            "success" -> current.copy(
                                showSessionPicker = if (operation == "create") false else current.showSessionPicker,
                                isSessionOperationPending = false,
                                sessionOperationMessage = null,
                                sessionOperationError = null
                            )
                            "error" -> {
                                val options = if (current.availableSessions.any { it.key == NEW_SESSION_KEY }) {
                                    current.availableSessions
                                } else {
                                    listOf(SessionPickerInfo(NEW_SESSION_KEY, "+ New Session")) + current.availableSessions
                                }
                                current.copy(
                                    showSessionPicker = true,
                                    availableSessions = options,
                                    selectedSessionIndex = current.selectedSessionIndex.coerceIn(options.indices),
                                    isSessionOperationPending = false,
                                    sessionOperationMessage = null,
                                    sessionOperationError = error ?: "Session operation failed"
                                )
                            }
                            else -> current
                        }
                    }
                    if (state != "loading") sessionPickerRequested = false
                    Log.d(GlassesApp.TAG, "Session operation: $operation/$state")
                }

                "model_page" -> {
                    val modelsArray = msg.optJSONArray("m")
                    val models = buildList {
                        if (modelsArray != null) {
                            for (index in 0 until modelsArray.length()) {
                                val model = modelsArray.optJSONObject(index) ?: continue
                                add(
                                    ModelPickerInfo(
                                        index = model.optInt("i", -1),
                                        name = model.optString("n", "Model"),
                                        provider = model.optString("p", ""),
                                        available = model.optBoolean("a", true),
                                    )
                                )
                            }
                        }
                    }.filter { it.index >= 0 }
                    val currentIndex = msg.optInt("ci", -1).takeIf { it >= 0 }
                    val currentIndexOnPage = models.indexOfFirst { it.index == currentIndex }
                        .takeIf { it >= 0 }
                    val selectedIndex = ModelPickerNavigation.initialIndex(
                        itemCount = models.size,
                        currentIndexOnPage = currentIndexOnPage,
                        pageSelection = pendingModelPageSelection,
                    )
                    hudState.update { current ->
                        current.copy(
                            showModelPicker = current.showModelPicker || modelPickerRequested,
                            availableModels = models,
                            modelCatalogId = msg.optString("c").takeIf { it.isNotBlank() },
                            currentModelIndex = currentIndex,
                            selectedModelIndex = selectedIndex,
                            modelPageOffset = msg.optInt("o", 0),
                            modelNextOffset = msg.optInt("x", -1).takeIf { it >= 0 },
                            modelPageIndex = msg.optInt("pi", 0),
                            modelPageCount = msg.optInt("pc", 1).coerceAtLeast(1),
                            isModelOperationPending = false,
                            modelOperationMessage = null,
                            modelOperationError = msg.optString("e").takeIf { it.isNotBlank() },
                        )
                    }
                    modelPickerRequested = false
                    pendingModelPageSelection = ModelPageSelection.CURRENT
                    Log.d(GlassesApp.TAG, "Model page received (${models.size} entries)")
                }

                "model_operation" -> {
                    val operationState = msg.optString("state")
                    hudState.update { current ->
                        when (operationState) {
                            "loading" -> current.copy(
                                showModelPicker = true,
                                isModelOperationPending = true,
                                modelOperationMessage = "Changing model...",
                                modelOperationError = null,
                            )
                            "success" -> current.copy(
                                showModelPicker = false,
                                currentModelIndex = msg.optInt("ci", current.currentModelIndex ?: -1)
                                    .takeIf { it >= 0 },
                                isModelOperationPending = false,
                                modelOperationMessage = null,
                                modelOperationError = null,
                            )
                            "error" -> current.copy(
                                showModelPicker = true,
                                isModelOperationPending = false,
                                modelOperationMessage = null,
                                modelOperationError = msg.optString("error")
                                    .takeIf { it.isNotBlank() }
                                    ?: "Could not change model",
                            )
                            else -> current
                        }
                    }
                    if (operationState != "loading") modelPickerRequested = false
                    Log.d(GlassesApp.TAG, "Model operation: $operationState")
                }

                "agent_list" -> {
                    val agentsArray = msg.optJSONArray("agents")
                    val agents = mutableListOf<AgentPickerInfo>()
                    if (agentsArray != null) {
                        for (i in 0 until agentsArray.length()) {
                            val agent = agentsArray.optJSONObject(i) ?: continue
                            val id = agent.optString("id", "").takeIf { it.isNotBlank() } ?: continue
                            agents += AgentPickerInfo(
                                id = id,
                                name = agent.optString("name", id).ifBlank { id },
                                model = agent.optString("model", "").takeIf { it.isNotBlank() }
                            )
                        }
                    }
                    val currentAgentId = msg.optString("currentAgentId", "")
                        .takeIf { it.isNotBlank() }
                    hudState.value = HudStateReducer.reduce(
                        hudState.value,
                        HudStateEvent.AgentsLoaded(
                            agents = agents,
                            currentAgentId = currentAgentId,
                            showPicker = agentPickerRequested,
                        ),
                    ).state
                    agentPickerRequested = false
                    Log.d(GlassesApp.TAG, "Agent list received (${agents.size} entries)")
                }

                "voice_state" -> {
                    val state = msg.optString("state", "")
                    val text = msg.optString("text", "")
                    val mode = if (msg.has("mode") && !msg.isNull("mode")) msg.getString("mode") else null
                    voiceHandler.handleVoiceState(state, text, mode)
                }

                "voice_result" -> {
                    val resultType = msg.optString("result_type", "text")
                    val text = msg.optString("text", "")
                    // Stage text FIRST, then update voice handler state.
                    // stageVoiceText atomically updates staging AND clears voice UI,
                    // preventing a race where the voice state collector overwrites
                    // the staging text by reading stale state.
                    when (resultType) {
                        "text" -> {
                            val trimmed = text.trim()
                            if (trimmed.isNotEmpty() && !msg.optBoolean("autoSent", false)) {
                                stageVoiceText(trimmed)
                            }
                        }
                        "command" -> handleVoiceCommand(text)
                    }
                    // Clear callback and ensure voice handler knows we're done
                    voiceHandler.handleVoiceResult(resultType, text)
                }

                "photo_result" -> {
                    val status = msg.optString("status", "")
                    if (status == "captured") {
                        val thumbnailBase64 = msg.optString("thumbnail", "")
                        if (thumbnailBase64.isNotEmpty()) {
                            val current = hudState.value
                            if (current.photoThumbnails.size >= MAX_PHOTOS) {
                                Log.w(GlassesApp.TAG, "Max $MAX_PHOTOS photos reached, ignoring photo_result")
                            } else {
                                val thumbnail = ThumbnailBitmapCache.decode(
                                    encoded = thumbnailBase64,
                                    format = msg.optString("thumbnailFormat").takeIf { it.isNotBlank() },
                                    width = msg.optInt("thumbnailWidth"),
                                    height = msg.optInt("thumbnailHeight"),
                                )
                                if (thumbnail != null) {
                                    hudState.value = current.copy(
                                        photoThumbnails = current.photoThumbnails + thumbnail,
                                        focusedArea = ChatFocusArea.INPUT,
                                        inputActionIndex = current.photoThumbnails.size + 2,
                                    )
                                    Log.d(GlassesApp.TAG, "Photo captured, thumbnail added (total: ${current.photoThumbnails.size + 1})")
                                } else {
                                    Log.w(GlassesApp.TAG, "Captured photo thumbnail could not be decoded")
                                }
                            }
                        }
                    } else {
                        Log.e(GlassesApp.TAG, "Photo capture failed")
                    }
                }

                "remove_photo" -> {
                    val all = msg.optBoolean("all", false)
                    val current = hudState.value
                    if (all) {
                        hudState.value = current.copy(
                            photoThumbnails = emptyList()
                        )
                    } else {
                        val index = msg.optInt("index", -1)
                        if (index in current.photoThumbnails.indices) {
                            val updated = current.photoThumbnails.toMutableList().apply { removeAt(index) }
                            val maxIndex = updated.size + 1 // Send index
                            hudState.value = current.copy(
                                photoThumbnails = updated,
                                inputActionIndex = minOf(current.inputActionIndex, maxIndex)
                            )
                        }
                    }
                    Log.d(GlassesApp.TAG, "Photo removed from phone request")
                }

                "wake_signal" -> {
                    // Phone is sending a wake signal — display wake is handled by the
                    // phone via CXR SDK (setGlassBrightness). Glasses side just shows
                    // the notification and sends ack.
                    val reason = msg.optString("reason", "")
                    val bufferedCount = msg.optInt("bufferedCount", 0)
                    Log.i(GlassesApp.TAG, "Wake signal received (buffered=$bufferedCount)")

                    // Show wake notification briefly
                    showWakeNotification(reason)

                    // Send acknowledgment back to phone
                    phoneConnection.sendToPhone("""{"type":"wake_ack","ready":true,"timestamp":${System.currentTimeMillis()}}""")
                    Log.d(GlassesApp.TAG, "Sent wake_ack to phone")
                }

                "tts_state" -> {
                    // TTS state sync from phone
                    val enabled = msg.optBoolean("enabled", false)
                    val voiceName = if (msg.has("voiceName") && !msg.isNull("voiceName")) {
                        msg.getString("voiceName")
                    } else null
                    hudState.update { current ->
                        current.copy(
                            ttsEnabled = enabled,
                            ttsPlaybackState = msg.optString("playbackState", "idle"),
                            ttsCanReplay = msg.optBoolean("canReplay", false),
                        )
                    }
                    Log.d(GlassesApp.TAG, "TTS state: enabled=$enabled, voice=$voiceName")
                }

                "run_state" -> {
                    val runState = msg.optString("state", "idle")
                    hudState.value = HudStateReducer.reduce(
                        hudState.value,
                        HudStateEvent.RunChanged(
                            state = runState,
                            canAbort = msg.optBoolean("canAbort", false),
                        ),
                    ).state
                }

                "talk_mode_state" -> {
                    hudState.value = HudStateReducer.reduce(
                        hudState.value,
                        HudStateEvent.TalkModeChanged(
                            enabled = msg.optBoolean("enabled", false),
                            phase = msg.optString("phase", "off"),
                        ),
                    ).state
                }

                "hud_card" -> {
                    val actionsJson = msg.optJSONArray("actions")
                    val actions = buildList {
                        if (actionsJson != null) {
                            for (index in 0 until actionsJson.length()) {
                                val action = actionsJson.optJSONObject(index) ?: continue
                                add(HudCardActionDisplay(action.optString("id"), action.optString("label")))
                            }
                        }
                    }.filter { it.id.isNotBlank() && it.label.isNotBlank() }
                    val card = HudCardDisplay(
                        id = msg.optString("id"),
                        source = msg.optString("source", "Clawsses"),
                        title = msg.optString("title", "Update"),
                        body = msg.optString("body"),
                        priority = msg.optString("priority", "normal"),
                        expiresAt = msg.optLong("expiresAt", 0L).takeIf { it > 0L },
                        actions = actions,
                    )
                    if (card.id.isNotBlank() && card.body.isNotBlank()) {
                        hudState.update { current ->
                            current.copy(
                                hudCards = (current.hudCards.filterNot { it.id == card.id } + card).takeLast(5),
                                selectedHudCardActionIndex = 0,
                            )
                        }
                        scheduleActiveCardExpiry()
                    }
                }

                "live_caption" -> {
                    val enabled = msg.optBoolean("enabled", false)
                    val caption = LiveCaptionDisplay(
                        sourceText = msg.optString("sourceText"),
                        translatedText = msg.optString("translatedText").takeIf { it.isNotBlank() },
                        sourceLanguage = msg.optString("sourceLanguage").takeIf { it.isNotBlank() },
                        targetLanguage = msg.optString("targetLanguage").takeIf { it.isNotBlank() },
                        error = msg.optString("error").takeIf { it.isNotBlank() },
                    )
                    hudState.value = HudStateReducer.reduce(
                        hudState.value,
                        HudStateEvent.LiveCaptionChanged(enabled, caption),
                    ).state
                }

                else -> {
                    Log.d(GlassesApp.TAG, "Unknown message type: $type")
                }
            }
            if (transactionId != null) {
                processedTransportTransactions.addLast(transactionId)
                while (processedTransportTransactions.size > 64) {
                    processedTransportTransactions.removeFirst()
                }
                phoneConnection.sendToPhone("""{"type":"transport_ack","tx":"$transactionId"}""")
            }
        } catch (e: Exception) {
            Log.e(GlassesApp.TAG, "Error parsing phone message (${json.length} chars)", e)
        }
    }

    private fun parseAttachmentThumbnails(message: JSONObject): List<Bitmap> {
        val attachments = message.optJSONArray("attachments") ?: return emptyList()
        val thumbnails = mutableListOf<Bitmap>()
        for (index in 0 until minOf(attachments.length(), MAX_PHOTOS)) {
            val attachment = attachments.optJSONObject(index) ?: continue
            val encoded = attachment.optString("thumbnail")
                ?.takeIf { it.isNotBlank() }
                ?: continue
            val bitmap = ThumbnailBitmapCache.decode(
                encoded = encoded,
                format = attachment.optString("thumbnailFormat").takeIf { it.isNotBlank() },
                width = attachment.optInt("thumbnailWidth"),
                height = attachment.optInt("thumbnailHeight"),
            )
            if (bitmap != null) thumbnails += bitmap
        }
        return thumbnails
    }

    private fun publishStreamingMessage() {
        streamingAccumulator.snapshotIfChanged()?.let { streamingMessage.value = it }
    }

    private fun clearStreamingMessage(id: String? = null) {
        streamingAccumulator.clear(id)
        if (id == null || streamingMessage.value?.id == id) {
            streamPublishJob?.cancel()
            streamPublishJob = null
            streamingMessage.value = null
        }
    }

    /**
     * Unwrap soft line breaks from AI model output so Compose can re-wrap
     * to the actual widget width. Preserves paragraph breaks (blank lines),
     * list items, and other structural markdown.
     *
     * A single `\n` between two non-empty, non-structural lines is treated
     * as a soft wrap inserted by the model and replaced with a space.
     */
    private fun unwrapContent(text: String): String {
        val lines = text.split("\n")
        if (lines.size <= 1) return text

        val result = StringBuilder()
        for (i in lines.indices) {
            val line = lines[i]
            result.append(line)
            if (i < lines.lastIndex) {
                val next = lines[i + 1]
                // Keep newline (don't join) when:
                // - current line is blank → paragraph break
                // - next line is blank → paragraph break
                // - next line starts with markdown structure (list, heading, code fence, blockquote)
                val keepNewline = line.isBlank() ||
                    next.isBlank() ||
                    next.trimStart().let {
                        it.startsWith("- ") ||
                        it.startsWith("* ") ||
                        it.startsWith("+ ") ||
                        it.matches(Regex("^\\d+[.)].+")) ||
                        it.startsWith("#") ||
                        it.startsWith("```") ||
                        it.startsWith("> ")
                    }

                if (keepNewline) {
                    result.append("\n")
                } else {
                    // Join with space (soft wrap from model)
                    if (line.isNotEmpty()) result.append(" ")
                }
            }
        }
        return result.toString()
    }

    override fun onDestroy() {
        if (aiStartReceiverRegistered) {
            unregisterReceiver(aiStartReceiver)
            aiStartReceiverRegistered = false
        }
        streamPublishJob?.cancel()
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
                .commit()
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
