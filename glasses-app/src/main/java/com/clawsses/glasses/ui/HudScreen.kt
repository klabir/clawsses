package com.clawsses.glasses.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.clawsses.glasses.R
import com.clawsses.shared.HudPageNavigator
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawsses.glasses.media.ThumbnailHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Display size presets for the 480x640 portrait HUD
 * Each preset optimizes for different character counts vs readability
 */
enum class HudDisplaySize(val fontSizeSp: Int, val label: String) {
    COMPACT(10, "Compact"),
    NORMAL(12, "Normal"),
    COMFORTABLE(14, "Comfortable"),
    LARGE(16, "Large")
}

/**
 * HUD position controls how much of the 480x640 display is used.
 * Smaller positions let the user see more of the outside world.
 */
enum class HudPosition(val label: String) {
    FULL("Full"),
    BOTTOM_HALF("Bottom"),
    TOP_HALF("Top")
}

data class HudTelemetry(
    val batteryLevel: Int? = null,
    val batteryCharging: Boolean = false,
    val currentTime: String = "",
)

/**
 * Focus areas of the chat UI.
 */
enum class ChatFocusArea {
    CONTENT,  // Chat messages (scrollable)
    INPUT,    // Voice input staging area (photos + Send / Clear buttons)
    MENU      // Bottom menu bar
}

/**
 * Action buttons in the input staging area
 */
enum class InputActionItem(val icon: String, val label: String) {
    SEND("\u21B5", "Send"),
    CLEAR("\u2715", "Clear")
}

/** Maximum number of photos that can be attached. */
const val MAX_PHOTOS = 4

/**
 * Agent response states
 */
enum class AgentState {
    IDLE,       // No active request
    THINKING,   // Ack received, waiting for first chunk
    REASONING,  // Gateway reports a private reasoning phase (content is not forwarded)
    STREAMING,  // Receiving streaming chunks
    ABORTING    // A targeted chat.abort request is in flight
}

/**
 * Menu bar items
 */
enum class MenuBarItem(val icon: String, val label: String) {
    PHOTO("\uD83D\uDCF7", "Photo"),
    SESSION("\u25CE", "Sess"),
    MODEL("\u25C6", "Model"),
    SIZE("\u2588", "Size"),  // Icon overridden dynamically based on next HudPosition
    MORE("\u2026", "More"),
}

/**
 * Items available in the MORE menu
 */
enum class MoreMenuItem(val icon: String, val label: String, val displaySize: HudDisplaySize? = null) {
    FONT_COMPACT("Aa", "Compact", HudDisplaySize.COMPACT),
    FONT_NORMAL("Aa", "Normal", HudDisplaySize.NORMAL),
    FONT_COMFORTABLE("Aa", "Comfortable", HudDisplaySize.COMFORTABLE),
    FONT_LARGE("Aa", "Large", HudDisplaySize.LARGE),
    AGENT("\u25C6", "Agent"),
    SLASH("/", "Slash Cmds"),
    TALK("\u25C9", "Talk Mode"),
    CAPTIONS("CC", "Live Captions"),
    VOICE("\uD83D\uDD0A", "Voice"),  // speaker icon - label is dynamic
    TTS_STOP("\u25A0", "Stop Voice"),
    TTS_REPLAY("\u21BB", "Replay Voice"),
    STOP_RUN("\u25A0", "Stop Run"),
}

/**
 * A display-ready chat message for the HUD.
 * Stores raw content; wrapping is computed at render time.
 */
data class DisplayMessage(
    val id: String,
    val role: String,  // "user" or "assistant"
    val content: String,
    val isStreaming: Boolean = false,
    val thumbnails: List<ThumbnailHandle> = emptyList()
)

/**
 * Recognition mode indicator (OpenAI vs device)
 */
enum class RecognitionMode {
    DEVICE,  // Android's SpeechRecognizer
    OPENAI   // OpenAI Realtime API
}

/**
 * Voice input states for HUD display
 */
sealed class VoiceInputState {
    object Idle : VoiceInputState()
    data class Listening(val mode: RecognitionMode = RecognitionMode.DEVICE) : VoiceInputState()
    data class Recognizing(val mode: RecognitionMode = RecognitionMode.DEVICE) : VoiceInputState()
    data class Processing(val mode: RecognitionMode = RecognitionMode.DEVICE) : VoiceInputState()
    data class Error(val message: String) : VoiceInputState()
}

/**
 * Session info for session picker
 */
data class SessionPickerInfo(
    val key: String,
    val name: String,
    val kind: String? = null,
    val hasUnread: Boolean = false,
    val updatedAt: Long? = null
)

data class AgentPickerInfo(
    val id: String,
    val name: String,
    val model: String? = null
)

data class ModelPickerInfo(
    val index: Int,
    val name: String,
    val provider: String,
    val available: Boolean,
)

fun visibleMoreMenuItems(showAgentSelector: Boolean): List<MoreMenuItem> =
    MoreMenuItem.entries.filter { item -> item != MoreMenuItem.AGENT || showAgentSelector }

data class AgentProgressDisplay(
    val id: String,
    val kind: String,
    val label: String,
    val state: String,
)

data class HudCardActionDisplay(val id: String, val label: String)

data class HudCardDisplay(
    val id: String,
    val source: String,
    val title: String,
    val body: String,
    val priority: String,
    val expiresAt: Long?,
    val actions: List<HudCardActionDisplay>,
)

data class LiveCaptionDisplay(
    val sourceText: String = "",
    val translatedText: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val error: String? = null,
)

/** Format a millisecond epoch timestamp as a short relative time string. */
internal fun formatRelativeTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = now - timestampMs
    if (diffMs < 0) return "now"
    val seconds = diffMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "yesterday"
        days < 30 -> "${days}d ago"
        else -> "${days / 30}mo ago"
    }
}

/**
 * Chat HUD state — replaces the old TerminalState
 */
data class ChatHudState(
    val messages: List<DisplayMessage> = emptyList(),
    val scrollPosition: Int = 0,
    val scrollTrigger: Int = 0,
    val isScrolledToEnd: Boolean = false,
    val pageIndex: Int = 0,
    val pageCount: Int = 1,
    val pageNavigationDelta: Int = 0,
    val pageNavigationToLatest: Boolean = false,
    val pageNavigationHold: Boolean = false,
    val pageNavigationTrigger: Int = 0,
    val inputText: String = "",
    val photoThumbnails: List<ThumbnailHandle> = emptyList(),
    val isConnected: Boolean = false,
    val agentState: AgentState = AgentState.IDLE,
    val agentProgress: List<AgentProgressDisplay> = emptyList(),
    val menuBarIndex: Int = 0,
    val hudPosition: HudPosition = HudPosition.FULL,
    val displaySize: HudDisplaySize = HudDisplaySize.NORMAL,
    val focusedArea: ChatFocusArea = ChatFocusArea.CONTENT,
    val voiceState: VoiceInputState = VoiceInputState.Idle,
    val voiceText: String = "",
    // Session picker
    val showSessionPicker: Boolean = false,
    val availableSessions: List<SessionPickerInfo> = emptyList(),
    val currentSessionKey: String? = null,
    val currentSessionName: String? = null,
    val selectedSessionIndex: Int = 0,
    val isSessionOperationPending: Boolean = false,
    val sessionOperationMessage: String? = null,
    val sessionOperationError: String? = null,
    // Agent picker
    val showAgentPicker: Boolean = false,
    val availableAgents: List<AgentPickerInfo> = emptyList(),
    val currentAgentId: String? = null,
    val currentAgentName: String? = null,
    val selectedAgentIndex: Int = 0,
    // Model picker
    val showModelPicker: Boolean = false,
    val availableModels: List<ModelPickerInfo> = emptyList(),
    val modelCatalogId: String? = null,
    val currentModelIndex: Int? = null,
    val selectedModelIndex: Int = 0,
    val modelPageOffset: Int = 0,
    val modelNextOffset: Int? = null,
    val modelPageIndex: Int = 0,
    val modelPageCount: Int = 1,
    val isModelOperationPending: Boolean = false,
    val modelOperationMessage: String? = null,
    val modelOperationError: String? = null,
    // More menu
    val showMoreMenu: Boolean = false,
    val selectedMoreIndex: Int = 0,
    // Slash command menu
    val showSlashMenu: Boolean = false,
    val selectedSlashIndex: Int = 0,
    // Input staging area (voice text accumulation)
    val stagingText: String = "",
    val showInputStaging: Boolean = false,
    val inputActionIndex: Int = 0,  // Index into combined row: [photo0..N-1, Clear, Send]. Default = Send (last)
    // Exit confirmation dialog
    val showExitConfirm: Boolean = false,
    // History loading state
    val isLoadingMoreHistory: Boolean = false,
    val hasMoreHistory: Boolean = true,  // Assume there's more until we're told otherwise
    val newPrependCount: Int = 0,  // Number of newly prepended messages (for fade-in animation)
    // Wake notification (shown briefly when glasses wakes from standby due to new content)
    val showWakeNotification: Boolean = false,
    val wakeReason: String? = null,  // "stream_content", "new_message", "cron_message"
    // TTS state (voice responses)
    val ttsEnabled: Boolean = false,
    val ttsPlaybackState: String = "idle",
    val ttsCanReplay: Boolean = false,
    val runState: String = "idle",
    val runCanAbort: Boolean = false,
    val talkModeEnabled: Boolean = false,
    val talkModePhase: String = "off",
    val hudCards: List<HudCardDisplay> = emptyList(),
    val selectedHudCardActionIndex: Int = 0,
    val liveCaptionEnabled: Boolean = false,
    val liveCaption: LiveCaptionDisplay? = null,
) {
    /** Total number of messages */
    val totalMessages: Int get() = messages.size
}

/**
 * Slash command with display label.
 * Commands are sent to the OpenClaw Gateway as chat messages.
 */
data class SlashCommandItem(val command: String, val description: String)

/**
 * Available slash commands from the OpenClaw Gateway.
 * See .openclaw-ref/docs/tools/slash-commands.md for the full reference.
 */
val SLASH_COMMANDS = listOf(
    SlashCommandItem("/help", "Show help"),
    SlashCommandItem("/commands", "List commands"),
    SlashCommandItem("/status", "Show status"),
    SlashCommandItem("/model", "Switch model"),
    SlashCommandItem("/compact", "Compact context"),
    SlashCommandItem("/reset", "New session"),
    SlashCommandItem("/stop", "Stop generation"),
    SlashCommandItem("/think", "Thinking level"),
    SlashCommandItem("/context", "Show context"),
    SlashCommandItem("/usage", "Usage info"),
    SlashCommandItem("/whoami", "Show identity"),
    SlashCommandItem("/reasoning", "Toggle reasoning"),
    SlashCommandItem("/elevated", "Elevated mode"),
    SlashCommandItem("/verbose", "Verbose output"),
    SlashCommandItem("/exec", "Exec settings"),
    SlashCommandItem("/subagents", "Sub-agents"),
)

/**
 * Keep bright HUD text away from the upper optical/scan boundary. Content drawn
 * in the first few rows can produce a vertically mirrored ghost on the physical
 * Rokid micro-LED display even though Compose renders the row only once.
 */
private val HudTopSafeInset = 24.dp
private val HudBottomSafeInset = 32.dp

// ============================================================================
// MAIN HUD SCREEN
// ============================================================================

/**
 * Chat-oriented HUD display for Rokid Glasses with OpenClaw backend.
 *
 * Layout:
 * ┌─[TopBar]──────────────────────────────────┐
 * │ ● connected                    12/42 lines │
 * ├────────────────────────────────────────────┤
 * │ Assistant message (left-aligned, green)     │
 * │         User message (right, light bg) │
 * │ Assistant streaming...█                     │
 * ├───[Input]──────────────────────────────────┤
 * │ > current input text...                     │
 * ├───[Menu Bar]───────────────────────────────┤
 * │ ↵Enter ⌫Clear ◎Sess ⬚Size AaFont …More    │
 * └────────────────────────────────────────────┘
 */
@Composable
fun HudScreen(
    state: HudUiState,
    telemetry: StateFlow<HudTelemetry>,
    streamingMessage: StateFlow<HudStreamingSnapshot?>,
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onScrolledToEndChanged: (Boolean) -> Unit = {},
    onPageStateChanged: (pageIndex: Int, pageCount: Int, atStart: Boolean, atEnd: Boolean) -> Unit =
        { _, _, _, _ -> },
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val monoFontFamily = remember { FontFamily(Font(R.font.jetbrains_mono)) }

    // Focus brightness
    val contentFocused = state.focusedArea == ChatFocusArea.CONTENT
    val inputFocused = state.focusedArea == ChatFocusArea.INPUT
    val menuFocused = state.focusedArea == ChatFocusArea.MENU

    val contentAlpha = focusBrightness(contentFocused)
    val inputAlpha = focusBrightness(inputFocused)
    val menuAlpha = focusBrightness(menuFocused)

    // HUD position offset
    val hudHeight = when (state.hudPosition) {
        HudPosition.FULL -> 1f
        HudPosition.BOTTOM_HALF, HudPosition.TOP_HALF -> 0.5f
    }
    val hudAlignment = when (state.hudPosition) {
        HudPosition.FULL -> Alignment.TopStart
        HudPosition.BOTTOM_HALF -> Alignment.BottomStart
        HudPosition.TOP_HALF -> Alignment.TopStart
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onDoubleTap() },
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        // Calculate font size to fit content width — varies with displaySize
        val targetColumns = when (state.displaySize) {
            HudDisplaySize.COMPACT -> 70
            HudDisplaySize.NORMAL -> 60
            HudDisplaySize.COMFORTABLE -> 50
            HudDisplaySize.LARGE -> 40
        }
        val referenceText = "M".repeat(targetColumns)
        val referenceFontSize = 12.sp

        val fontSize = remember(maxWidth, monoFontFamily, targetColumns) {
            val referenceStyle = TextStyle(
                fontFamily = monoFontFamily,
                fontSize = referenceFontSize,
                letterSpacing = 0.sp
            )
            val measuredWidth = textMeasurer.measure(referenceText, referenceStyle).size.width
            val availableWidthPx = with(density) { maxWidth.toPx() }
            val scaledSize = referenceFontSize.value * (availableWidthPx / measuredWidth) * 0.99f
            scaledSize.coerceIn(6f, 24f).sp
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = hudAlignment
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(hudHeight)
                    .padding(
                        start = 12.dp,
                        top = HudTopSafeInset,
                        end = 12.dp,
                        bottom = HudBottomSafeInset,
                    )
            ) {
                // TOP BAR
                TopBar(
                    isConnected = state.isConnected,
                    scrollInfo = buildString {
                        append("P ")
                        append(state.pageIndex + 1)
                        append('/')
                        append(state.pageCount.coerceAtLeast(1))
                        if (!state.isScrolledToEnd) append(" ↓")
                    },
                    agentState = state.agentState,
                    focusedArea = state.focusedArea,
                    voiceState = state.voiceState,
                    sessionTitle = state.currentSessionName,
                    isLoadingMoreHistory = state.isLoadingMoreHistory,
                    showWakeNotification = state.showWakeNotification,
                    wakeReason = state.wakeReason,
                    talkModeEnabled = state.talkModeEnabled,
                    talkModePhase = state.talkModePhase,
                    fontFamily = monoFontFamily,
                    fontSize = fontSize
                )

                AnimatedVisibility(
                    visible = state.voiceState !is VoiceInputState.Idle,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    VoiceActivityBanner(
                        voiceState = state.voiceState,
                        fontFamily = monoFontFamily,
                        fontSize = fontSize,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // CONTENT AREA — chat messages
                PagedChatContentArea(
                    messages = state.messages,
                    streamingMessage = streamingMessage,
                    agentState = state.agentState,
                    progressItems = state.agentProgress,
                    fontSize = fontSize,
                    fontFamily = monoFontFamily,
                    alpha = contentAlpha,
                    hasMoreHistory = state.hasMoreHistory,
                    sessionKey = state.currentSessionKey,
                    pageNavigationDelta = state.pageNavigationDelta,
                    pageNavigationToLatest = state.pageNavigationToLatest,
                    pageNavigationHold = state.pageNavigationHold,
                    pageNavigationTrigger = state.pageNavigationTrigger,
                    onPageStateChanged = { pageIndex, pageCount, atStart, atEnd ->
                        onPageStateChanged(pageIndex, pageCount, atStart, atEnd)
                        onScrolledToEndChanged(atEnd)
                    },
                    modifier = Modifier.weight(1f)
                )

                // INPUT STAGING AREA (with inline photo thumbnails)
                AnimatedVisibility(
                    visible = state.showInputStaging || state.photoThumbnails.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    InputStagingArea(
                        text = state.stagingText,
                        showText = state.showInputStaging,
                        photos = state.photoThumbnails,
                        selectedIndex = state.inputActionIndex,
                        isFocused = inputFocused,
                        isProcessing = state.voiceState is VoiceInputState.Processing,
                        fontFamily = monoFontFamily,
                        fontSize = fontSize,
                        alpha = inputAlpha
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // MENU BAR
                ChatMenuBar(
                    selectedIndex = state.menuBarIndex,
                    isFocused = menuFocused,
                    hudPosition = state.hudPosition,
                    telemetry = telemetry,
                    fontFamily = monoFontFamily,
                    alpha = menuAlpha
                )
            }
        }

        // Session picker overlay
        AnimatedVisibility(
            visible = state.showSessionPicker,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SessionPickerOverlay(
                sessions = state.availableSessions,
                currentSessionKey = state.currentSessionKey,
                selectedIndex = state.selectedSessionIndex,
                isPending = state.isSessionOperationPending,
                statusMessage = state.sessionOperationMessage,
                errorMessage = state.sessionOperationError,
                fontFamily = monoFontFamily
            )
        }

        AnimatedVisibility(
            visible = state.showModelPicker,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ModelPickerOverlay(
                models = state.availableModels,
                currentModelIndex = state.currentModelIndex,
                selectedIndex = state.selectedModelIndex,
                pageIndex = state.modelPageIndex,
                pageCount = state.modelPageCount,
                isPending = state.isModelOperationPending,
                statusMessage = state.modelOperationMessage,
                errorMessage = state.modelOperationError,
                fontFamily = monoFontFamily,
            )
        }

        AnimatedVisibility(
            visible = state.showAgentPicker,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            AgentPickerOverlay(
                agents = state.availableAgents,
                currentAgentId = state.currentAgentId,
                selectedIndex = state.selectedAgentIndex,
                fontFamily = monoFontFamily
            )
        }

        // More menu overlay
        AnimatedVisibility(
            visible = state.showMoreMenu,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            MoreMenuOverlay(
                selectedIndex = state.selectedMoreIndex,
                currentDisplaySize = state.displaySize,
                ttsEnabled = state.ttsEnabled,
                ttsPlaybackState = state.ttsPlaybackState,
                ttsCanReplay = state.ttsCanReplay,
                runState = state.runState,
                runCanAbort = state.runCanAbort,
                talkModeEnabled = state.talkModeEnabled,
                liveCaptionEnabled = state.liveCaptionEnabled,
                showAgentSelector = state.availableAgents.size > 1,
                fontFamily = monoFontFamily
            )
        }

        AnimatedVisibility(
            visible = state.hudCards.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            state.hudCards.firstOrNull()?.let { card ->
                HudCardOverlay(
                    card = card,
                    selectedActionIndex = state.selectedHudCardActionIndex,
                    queuedCount = state.hudCards.size,
                    fontFamily = monoFontFamily,
                )
            }
        }

        AnimatedVisibility(
            visible = state.liveCaptionEnabled && state.hudCards.isEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LiveCaptionOverlay(
                caption = state.liveCaption,
                fontFamily = monoFontFamily,
            )
        }

        // Slash command menu overlay
        AnimatedVisibility(
            visible = state.showSlashMenu,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SlashCommandOverlay(
                selectedIndex = state.selectedSlashIndex,
                fontFamily = monoFontFamily
            )
        }

        // Exit confirmation overlay
        AnimatedVisibility(
            visible = state.showExitConfirm,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ExitConfirmOverlay(fontFamily = monoFontFamily)
        }
    }
}

internal fun voiceActivityLabel(voiceState: VoiceInputState): String? = when (voiceState) {
    VoiceInputState.Idle -> null
    is VoiceInputState.Listening -> "LISTENING - SPEAK NOW"
    is VoiceInputState.Recognizing -> "LISTENING - SPEAK NOW"
    is VoiceInputState.Processing -> "PROCESSING..."
    is VoiceInputState.Error -> "VOICE ERROR: ${voiceState.message}"
}

@Composable
private fun VoiceActivityBanner(
    voiceState: VoiceInputState,
    fontFamily: FontFamily,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    val label = voiceActivityLabel(voiceState) ?: return
    val color = when (voiceState) {
        is VoiceInputState.Error -> HudColors.error
        is VoiceInputState.Processing -> HudColors.yellow
        else -> Color(0xFF64B5F6)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            fontFamily = fontFamily,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

// ============================================================================
// BRIGHTNESS ANIMATION
// ============================================================================

@Composable
fun focusBrightness(isFocused: Boolean): Float {
    val baseAlpha = if (isFocused) 1f else 0.4f
    return animateFloatAsState(
        targetValue = baseAlpha,
        animationSpec = tween(200),
        label = "brightness"
    ).value
}

// ============================================================================
// TOP BAR
// ============================================================================

@Composable
private fun TopBar(
    isConnected: Boolean,
    scrollInfo: String,
    agentState: AgentState,
    focusedArea: ChatFocusArea,
    voiceState: VoiceInputState,
    sessionTitle: String?,
    isLoadingMoreHistory: Boolean = false,
    showWakeNotification: Boolean = false,
    wakeReason: String? = null,
    talkModeEnabled: Boolean = false,
    talkModePhase: String = "off",
    fontFamily: FontFamily,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    val statusFontSize = (fontSize.value - 2).coerceAtLeast(8f).sp

    // Check if voice is active
    val isVoiceActive = voiceState is VoiceInputState.Listening ||
                        voiceState is VoiceInputState.Recognizing ||
                        voiceState is VoiceInputState.Processing ||
                        voiceState is VoiceInputState.Error

    // Get voice mode for display
    val voiceMode = when (voiceState) {
        is VoiceInputState.Listening -> voiceState.mode
        is VoiceInputState.Recognizing -> voiceState.mode
        is VoiceInputState.Processing -> voiceState.mode
        else -> null
    }

    // Animated dots for processing state
    val processingDots = if (voiceState is VoiceInputState.Processing) {
        var dotCount by remember { mutableIntStateOf(1) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(400)
                dotCount = (dotCount % 3) + 1
            }
        }
        ".".repeat(dotCount)
    } else {
        ""
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        // Connection dot + state label (left-aligned)
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "\u25CF",
                color = if (isConnected) HudColors.green else HudColors.error,
                fontSize = (statusFontSize.value + 2).sp
            )
            // Show voice state when active, wake notification, otherwise show agent state
            val stateLabel = when {
                showWakeNotification -> {
                    when (wakeReason) {
                        "stream_content" -> "\u26A1 streaming..."
                        "new_message" -> "\u26A1 new message"
                        "cron_message" -> "\u26A1 notification"
                        else -> "\u26A1 waking..."
                    }
                }
                voiceState is VoiceInputState.Listening -> {
                    val modeSuffix = if (voiceMode == RecognitionMode.OPENAI) " [AI]" else ""
                    "listening$modeSuffix..."
                }
                voiceState is VoiceInputState.Recognizing -> {
                    val modeSuffix = if (voiceMode == RecognitionMode.OPENAI) " [AI]" else ""
                    "recognizing$modeSuffix..."
                }
                voiceState is VoiceInputState.Processing -> {
                    val modeSuffix = if (voiceMode == RecognitionMode.OPENAI) " [AI]" else ""
                    "processing$modeSuffix $processingDots"
                }
                voiceState is VoiceInputState.Error -> "voice error"
                talkModeEnabled && agentState == AgentState.IDLE -> "talk $talkModePhase"
                isLoadingMoreHistory -> "loading..."
                agentState == AgentState.IDLE -> if (isConnected) "connected" else "disconnected"
                agentState == AgentState.THINKING -> "thinking..."
                agentState == AgentState.REASONING -> "reasoning..."
                agentState == AgentState.STREAMING -> "streaming..."
                agentState == AgentState.ABORTING -> "stopping..."
                else -> ""
            }
            Text(
                text = stateLabel,
                color = when {
                    showWakeNotification -> HudColors.yellow  // Yellow for wake notification (attention-grabbing)
                    isVoiceActive && voiceMode == RecognitionMode.OPENAI -> Color(0xFF64B5F6)  // Light blue for OpenAI
                    isVoiceActive -> HudColors.yellow  // Yellow for device/fallback voice
                    isLoadingMoreHistory -> HudColors.cyan
                    else -> HudColors.dimText
                },
                fontSize = statusFontSize,
                fontFamily = fontFamily
            )
        }

        // Session title (centered)
        if (!sessionTitle.isNullOrEmpty()) {
            Text(
                text = sessionTitle,
                color = HudColors.primaryText,
                fontSize = statusFontSize,
                fontFamily = fontFamily,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.45f)
            )
        }

        // Mode indicator + scroll info (right-aligned)
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (modeLabel, modeColor) = when (focusedArea) {
                ChatFocusArea.CONTENT -> "SCROLL" to HudColors.cyan
                ChatFocusArea.INPUT -> "INPUT" to HudColors.yellow
                ChatFocusArea.MENU -> "MENU" to HudColors.green
            }
            Text(
                text = modeLabel,
                color = modeColor,
                fontSize = statusFontSize,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = scrollInfo,
                color = HudColors.dimText,
                fontSize = statusFontSize,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// CHAT CONTENT AREA
// ============================================================================

@Composable
private fun PagedChatContentArea(
    messages: List<DisplayMessage>,
    streamingMessage: StateFlow<HudStreamingSnapshot?>,
    agentState: AgentState,
    progressItems: List<AgentProgressDisplay>,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily,
    alpha: Float,
    hasMoreHistory: Boolean,
    sessionKey: String?,
    pageNavigationDelta: Int,
    pageNavigationToLatest: Boolean,
    pageNavigationHold: Boolean,
    pageNavigationTrigger: Int,
    onPageStateChanged: (pageIndex: Int, pageCount: Int, atStart: Boolean, atEnd: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeStream by streamingMessage.collectAsStateWithLifecycle()
    val displayMessages = remember(messages, activeStream) {
        val stream = activeStream ?: return@remember messages
        val streamedMessage = DisplayMessage(
            id = stream.id,
            role = "assistant",
            content = stream.content,
            isStreaming = true,
        )
        val existingIndex = messages.indexOfFirst { it.id == stream.id }
        if (existingIndex < 0) messages + streamedMessage
        else messages.toMutableList().also { it[existingIndex] = streamedMessage }
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val navigator = remember(sessionKey) { HudPageNavigator() }
    val paginationCache = remember(sessionKey) { HudPaginationCache() }
    var currentPageIndex by remember(sessionKey) { mutableIntStateOf(0) }
    var previousPages by remember(sessionKey) { mutableStateOf(emptyList<HudPage>()) }
    var lastHandledNavigationTrigger by remember(sessionKey) { mutableIntStateOf(pageNavigationTrigger) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .alpha(alpha)
    ) {
        val pageWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val pageHeightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
        val textStyle = remember(fontSize, fontFamily) {
            TextStyle(
                fontFamily = fontFamily,
                fontSize = fontSize,
                lineHeight = fontSize,
                letterSpacing = 0.sp,
            )
        }
        val pages = remember(displayMessages, pageWidthPx, pageHeightPx, textStyle, hasMoreHistory) {
            paginationCache.paginate(
                messages = displayMessages,
                textMeasurer = textMeasurer,
                textStyle = textStyle,
                pageWidthPx = pageWidthPx,
                pageHeightPx = pageHeightPx,
                assistantOuterPaddingPx = with(density) { 16.dp.roundToPx() },
                userOuterPaddingPx = with(density) { 40.dp.roundToPx() },
                horizontalInnerPaddingPx = with(density) { 12.dp.roundToPx() },
                verticalInnerPaddingPx = with(density) { 4.dp.roundToPx() },
                messageSpacingPx = with(density) { 4.dp.roundToPx() },
                thumbnailHeightPx = with(density) { 20.dp.roundToPx() },
                historyMarkerHeightPx = with(density) { 32.dp.roundToPx() },
                showHistoryStart = !hasMoreHistory && displayMessages.isNotEmpty(),
            )
        }

        LaunchedEffect(pages, pageNavigationTrigger) {
            if (pages !== previousPages) {
                val wasAtLatest = previousPages.isEmpty() || navigator.pageIndex == previousPages.lastIndex
                val anchor = previousPages.getOrNull(navigator.pageIndex)?.anchor
                val restoredPage = findHudPageForAnchor(pages, anchor)
                navigator.onDocumentChanged(pages.size.coerceAtLeast(1), restoredPage)
                if (wasAtLatest) navigator.jumpToLatest(pages.size.coerceAtLeast(1))
                previousPages = pages
            }
            if (pageNavigationTrigger != lastHandledNavigationTrigger) {
                if (pageNavigationHold) {
                    navigator.holdCurrentPage()
                } else if (pageNavigationToLatest) {
                    navigator.jumpToLatest(pages.size.coerceAtLeast(1))
                } else {
                    navigator.moveBy(pageNavigationDelta, pages.size.coerceAtLeast(1))
                }
                lastHandledNavigationTrigger = pageNavigationTrigger
            }
            currentPageIndex = navigator.pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            val pageCount = pages.size.coerceAtLeast(1)
            onPageStateChanged(
                currentPageIndex,
                pageCount,
                currentPageIndex == 0,
                currentPageIndex == pageCount - 1,
            )
        }

        if (displayMessages.isEmpty() && agentState == AgentState.IDLE && progressItems.isEmpty()) {
            Text(
                text = "No messages yet",
                color = HudColors.dimText,
                fontSize = fontSize,
                fontFamily = fontFamily,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            val page = pages.getOrNull(currentPageIndex)
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (page?.showHistoryStart == true) {
                    HistoryStartIndicator(fontSize = fontSize, fontFamily = fontFamily)
                }
                page?.fragments?.forEach { fragment ->
                    ChatMessageItem(
                        message = fragment.message.copy(
                            content = fragment.content,
                            isStreaming = false,
                            thumbnails = if (fragment.showThumbnails) fragment.message.thumbnails else emptyList(),
                        ),
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                    )
                }
            }

            if (navigator.hasNewerPages(pages.size.coerceAtLeast(1))) {
                Text(
                    text = "↓ NEW TEXT",
                    color = HudColors.cyan,
                    fontSize = (fontSize.value - 2).coerceAtLeast(8f).sp,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            if (progressItems.isNotEmpty()) {
                AgentProgressPanel(
                    items = progressItems,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                )
            } else if (agentState == AgentState.THINKING || agentState == AgentState.REASONING) {
                ThinkingIndicator(
                    label = if (agentState == AgentState.REASONING) "reasoning" else "thinking",
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                )
            }
        }
    }
}

@Composable
private fun AgentProgressPanel(
    items: List<AgentProgressDisplay>,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.takeLast(3).forEach { item ->
            val marker = when (item.state) {
                "done" -> "✓"
                "error" -> "!"
                else -> "›"
            }
            Text(
                text = "$marker ${item.label}",
                color = when (item.state) {
                    "error" -> HudColors.error
                    "done" -> HudColors.dimText
                    else -> HudColors.cyan
                },
                fontSize = fontSize,
                fontFamily = fontFamily,
                softWrap = true,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ChatMessageItem(
    message: DisplayMessage,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily
) {
    val isUser = message.role == "user"
    val isStreaming = message.isStreaming

    // Blinking cursor for streaming
    val cursorVisible = if (isStreaming) {
        val infiniteTransition = rememberInfiniteTransition(label = "cursor")
        val cursorAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = tween(500)),
            label = "blink"
        )
        cursorAlpha > 0.5f
    } else {
        false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (isUser) {
                    it.padding(start = 40.dp)
                } else {
                    it.padding(end = 16.dp)
                }
            },
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .let {
                    if (isUser) {
                        it.background(
                            HudColors.green.copy(alpha = 0.15f),
                            RoundedCornerShape(6.dp)
                        )
                    } else {
                        it
                    }
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Column {
                if (message.thumbnails.isNotEmpty()) {
                    PhotoThumbnailRow(thumbnails = message.thumbnails)
                }

                val displayText = if (message.content.isEmpty() && isStreaming) {
                    if (cursorVisible) "\u2588" else " "
                } else if (isStreaming && cursorVisible) {
                    "${message.content}\u2588"
                } else {
                    message.content
                }

                Text(
                    text = displayText,
                    color = if (isUser) HudColors.primaryText else HudColors.green,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    lineHeight = fontSize,
                    letterSpacing = 0.sp,
                    textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                    softWrap = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator(
    label: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily
) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(600)),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .padding(end = 16.dp)
            .graphicsLayer { this.alpha = alpha }
    ) {
        Text(
            text = "$label...",
            color = HudColors.cyan,
            fontSize = (fontSize.value + 2).sp,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun HistoryStartIndicator(
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "\u2500\u2500 beginning of conversation \u2500\u2500",
            color = HudColors.dimText,
            fontSize = fontSize,
            fontFamily = fontFamily
        )
    }
}
