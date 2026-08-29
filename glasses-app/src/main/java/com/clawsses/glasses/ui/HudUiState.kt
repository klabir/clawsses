package com.clawsses.glasses.ui

import androidx.compose.runtime.Immutable
import com.clawsses.glasses.media.ThumbnailHandle

@Immutable
data class HudChatSlice(
    val messages: List<DisplayMessage>,
    val pageIndex: Int,
    val pageCount: Int,
    val pageNavigationDelta: Int,
    val pageNavigationToLatest: Boolean,
    val pageNavigationHold: Boolean,
    val pageNavigationTrigger: Int,
    val isScrolledToEnd: Boolean,
    val agentState: AgentState,
    val agentProgress: List<AgentProgressDisplay>,
    val isLoadingMoreHistory: Boolean,
    val hasMoreHistory: Boolean,
    val currentSessionKey: String?,
)

@Immutable
data class HudInputSlice(
    val inputText: String,
    val photoThumbnails: List<ThumbnailHandle>,
    val focusedArea: ChatFocusArea,
    val voiceState: VoiceInputState,
    val voiceText: String,
    val stagingText: String,
    val showInputStaging: Boolean,
    val inputActionIndex: Int,
)

@Immutable
data class HudPickerSlice(
    val showSessionPicker: Boolean,
    val availableSessions: List<SessionPickerInfo>,
    val currentSessionName: String?,
    val selectedSessionIndex: Int,
    val isSessionOperationPending: Boolean,
    val sessionOperationMessage: String?,
    val sessionOperationError: String?,
    val showAgentPicker: Boolean,
    val availableAgents: List<AgentPickerInfo>,
    val currentAgentId: String?,
    val selectedAgentIndex: Int,
    val showModelPicker: Boolean,
    val availableModels: List<ModelPickerInfo>,
    val currentModelIndex: Int?,
    val selectedModelIndex: Int,
    val modelPageIndex: Int,
    val modelPageCount: Int,
    val isModelOperationPending: Boolean,
    val modelOperationMessage: String?,
    val modelOperationError: String?,
    val showMoreMenu: Boolean,
    val selectedMoreIndex: Int,
    val showSlashMenu: Boolean,
    val selectedSlashIndex: Int,
    val showExitConfirm: Boolean,
)

@Immutable
data class HudStatusSlice(
    val isConnected: Boolean,
    val menuBarIndex: Int,
    val hudPosition: HudPosition,
    val displaySize: HudDisplaySize,
    val showWakeNotification: Boolean,
    val wakeReason: String?,
    val ttsEnabled: Boolean,
    val ttsPlaybackState: String,
    val ttsCanReplay: Boolean,
    val runState: String,
    val runCanAbort: Boolean,
    val talkModeEnabled: Boolean,
    val talkModePhase: String,
    val hudCards: List<HudCardDisplay>,
    val selectedHudCardActionIndex: Int,
    val liveCaptionEnabled: Boolean,
    val liveCaption: LiveCaptionDisplay?,
)

@Immutable
data class HudUiState(
    val chat: HudChatSlice,
    val input: HudInputSlice,
    val pickers: HudPickerSlice,
    val status: HudStatusSlice,
) {
    val messages get() = chat.messages
    val pageIndex get() = chat.pageIndex
    val pageCount get() = chat.pageCount
    val pageNavigationDelta get() = chat.pageNavigationDelta
    val pageNavigationToLatest get() = chat.pageNavigationToLatest
    val pageNavigationHold get() = chat.pageNavigationHold
    val pageNavigationTrigger get() = chat.pageNavigationTrigger
    val isScrolledToEnd get() = chat.isScrolledToEnd
    val agentState get() = chat.agentState
    val agentProgress get() = chat.agentProgress
    val isLoadingMoreHistory get() = chat.isLoadingMoreHistory
    val hasMoreHistory get() = chat.hasMoreHistory
    val currentSessionKey get() = chat.currentSessionKey

    val inputText get() = input.inputText
    val photoThumbnails get() = input.photoThumbnails
    val focusedArea get() = input.focusedArea
    val voiceState get() = input.voiceState
    val voiceText get() = input.voiceText
    val stagingText get() = input.stagingText
    val showInputStaging get() = input.showInputStaging
    val inputActionIndex get() = input.inputActionIndex

    val showSessionPicker get() = pickers.showSessionPicker
    val availableSessions get() = pickers.availableSessions
    val currentSessionName get() = pickers.currentSessionName
    val selectedSessionIndex get() = pickers.selectedSessionIndex
    val isSessionOperationPending get() = pickers.isSessionOperationPending
    val sessionOperationMessage get() = pickers.sessionOperationMessage
    val sessionOperationError get() = pickers.sessionOperationError
    val showAgentPicker get() = pickers.showAgentPicker
    val availableAgents get() = pickers.availableAgents
    val currentAgentId get() = pickers.currentAgentId
    val selectedAgentIndex get() = pickers.selectedAgentIndex
    val showModelPicker get() = pickers.showModelPicker
    val availableModels get() = pickers.availableModels
    val currentModelIndex get() = pickers.currentModelIndex
    val selectedModelIndex get() = pickers.selectedModelIndex
    val modelPageIndex get() = pickers.modelPageIndex
    val modelPageCount get() = pickers.modelPageCount
    val isModelOperationPending get() = pickers.isModelOperationPending
    val modelOperationMessage get() = pickers.modelOperationMessage
    val modelOperationError get() = pickers.modelOperationError
    val showMoreMenu get() = pickers.showMoreMenu
    val selectedMoreIndex get() = pickers.selectedMoreIndex
    val showSlashMenu get() = pickers.showSlashMenu
    val selectedSlashIndex get() = pickers.selectedSlashIndex
    val showExitConfirm get() = pickers.showExitConfirm

    val isConnected get() = status.isConnected
    val menuBarIndex get() = status.menuBarIndex
    val hudPosition get() = status.hudPosition
    val displaySize get() = status.displaySize
    val showWakeNotification get() = status.showWakeNotification
    val wakeReason get() = status.wakeReason
    val ttsEnabled get() = status.ttsEnabled
    val ttsPlaybackState get() = status.ttsPlaybackState
    val ttsCanReplay get() = status.ttsCanReplay
    val runState get() = status.runState
    val runCanAbort get() = status.runCanAbort
    val talkModeEnabled get() = status.talkModeEnabled
    val talkModePhase get() = status.talkModePhase
    val hudCards get() = status.hudCards
    val selectedHudCardActionIndex get() = status.selectedHudCardActionIndex
    val liveCaptionEnabled get() = status.liveCaptionEnabled
    val liveCaption get() = status.liveCaption
}

fun ChatHudState.toHudUiState(): HudUiState = HudUiState(
    chat = HudChatSlice(
        messages = messages,
        pageIndex = pageIndex,
        pageCount = pageCount,
        pageNavigationDelta = pageNavigationDelta,
        pageNavigationToLatest = pageNavigationToLatest,
        pageNavigationHold = pageNavigationHold,
        pageNavigationTrigger = pageNavigationTrigger,
        isScrolledToEnd = isScrolledToEnd,
        agentState = agentState,
        agentProgress = agentProgress,
        isLoadingMoreHistory = isLoadingMoreHistory,
        hasMoreHistory = hasMoreHistory,
        currentSessionKey = currentSessionKey,
    ),
    input = HudInputSlice(
        inputText = inputText,
        photoThumbnails = photoThumbnails,
        focusedArea = focusedArea,
        voiceState = voiceState,
        voiceText = voiceText,
        stagingText = stagingText,
        showInputStaging = showInputStaging,
        inputActionIndex = inputActionIndex,
    ),
    pickers = HudPickerSlice(
        showSessionPicker = showSessionPicker,
        availableSessions = availableSessions,
        currentSessionName = currentSessionName,
        selectedSessionIndex = selectedSessionIndex,
        isSessionOperationPending = isSessionOperationPending,
        sessionOperationMessage = sessionOperationMessage,
        sessionOperationError = sessionOperationError,
        showAgentPicker = showAgentPicker,
        availableAgents = availableAgents,
        currentAgentId = currentAgentId,
        selectedAgentIndex = selectedAgentIndex,
        showModelPicker = showModelPicker,
        availableModels = availableModels,
        currentModelIndex = currentModelIndex,
        selectedModelIndex = selectedModelIndex,
        modelPageIndex = modelPageIndex,
        modelPageCount = modelPageCount,
        isModelOperationPending = isModelOperationPending,
        modelOperationMessage = modelOperationMessage,
        modelOperationError = modelOperationError,
        showMoreMenu = showMoreMenu,
        selectedMoreIndex = selectedMoreIndex,
        showSlashMenu = showSlashMenu,
        selectedSlashIndex = selectedSlashIndex,
        showExitConfirm = showExitConfirm,
    ),
    status = HudStatusSlice(
        isConnected = isConnected,
        menuBarIndex = menuBarIndex,
        hudPosition = hudPosition,
        displaySize = displaySize,
        showWakeNotification = showWakeNotification,
        wakeReason = wakeReason,
        ttsEnabled = ttsEnabled,
        ttsPlaybackState = ttsPlaybackState,
        ttsCanReplay = ttsCanReplay,
        runState = runState,
        runCanAbort = runCanAbort,
        talkModeEnabled = talkModeEnabled,
        talkModePhase = talkModePhase,
        hudCards = hudCards,
        selectedHudCardActionIndex = selectedHudCardActionIndex,
        liveCaptionEnabled = liveCaptionEnabled,
        liveCaption = liveCaption,
    ),
)
