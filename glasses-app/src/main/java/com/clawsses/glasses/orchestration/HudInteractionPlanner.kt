package com.clawsses.glasses.orchestration

import com.clawsses.glasses.input.GestureHandler.Gesture
import com.clawsses.glasses.ui.ChatFocusArea
import com.clawsses.glasses.ui.ChatHudState
import com.clawsses.glasses.ui.MenuBarItem

internal sealed interface HudInteractionAction {
    data object ScrollUp : HudInteractionAction
    data object ScrollDown : HudInteractionAction
    data object ScrollToBottom : HudInteractionAction
    data object StartVoice : HudInteractionAction
    data class ExecuteMenuItem(val item: MenuBarItem) : HudInteractionAction
    data class RemovePhoto(val index: Int) : HudInteractionAction
    data object RemoveAllPhotos : HudInteractionAction
    data object SubmitInput : HudInteractionAction
}

internal data class HudInteractionDecision(
    val state: ChatHudState,
    val actions: List<HudInteractionAction> = emptyList(),
)

/** Pure focus/navigation planner for the three primary HUD interaction areas. */
internal object HudInteractionPlanner {
    fun plan(
        state: ChatHudState,
        target: HudGestureTarget,
        gesture: Gesture,
    ): HudInteractionDecision = when (target) {
        HudGestureTarget.CONTENT -> planContent(state, gesture)
        HudGestureTarget.INPUT -> planInput(state, gesture)
        HudGestureTarget.MENU -> planMenu(state, gesture)
        else -> HudInteractionDecision(state)
    }

    private fun planContent(state: ChatHudState, gesture: Gesture): HudInteractionDecision =
        when (gesture) {
            Gesture.SWIPE_FORWARD -> HudInteractionDecision(
                state,
                listOf(HudInteractionAction.ScrollUp),
            )
            Gesture.SWIPE_BACKWARD -> if (
                state.pageIndex >= state.pageCount - 1 && state.isScrolledToEnd
            ) {
                HudInteractionDecision(focusAfterContent(state))
            } else {
                HudInteractionDecision(state, listOf(HudInteractionAction.ScrollDown))
            }
            Gesture.TAP -> HudInteractionDecision(
                state,
                listOf(HudInteractionAction.ScrollToBottom),
            )
            Gesture.DOUBLE_TAP -> HudInteractionDecision(focusAfterContent(state))
            Gesture.LONG_PRESS -> HudInteractionDecision(
                state,
                listOf(HudInteractionAction.StartVoice),
            )
        }

    private fun focusAfterContent(state: ChatHudState): ChatHudState =
        if (state.showInputStaging || state.photoThumbnails.isNotEmpty()) {
            state.copy(
                focusedArea = ChatFocusArea.INPUT,
                inputActionIndex = state.photoThumbnails.size + 1,
            )
        } else {
            state.copy(focusedArea = ChatFocusArea.MENU, menuBarIndex = 0)
        }

    private fun planInput(state: ChatHudState, gesture: Gesture): HudInteractionDecision {
        val photoCount = state.photoThumbnails.size
        val clearIndex = photoCount
        val sendIndex = photoCount + 1
        return when (gesture) {
            Gesture.SWIPE_FORWARD -> HudInteractionDecision(
                if (state.inputActionIndex == 0) {
                    state.copy(focusedArea = ChatFocusArea.CONTENT)
                } else {
                    state.copy(inputActionIndex = state.inputActionIndex - 1)
                },
            )
            Gesture.SWIPE_BACKWARD -> HudInteractionDecision(
                if (state.inputActionIndex >= sendIndex) {
                    state.copy(focusedArea = ChatFocusArea.MENU, menuBarIndex = 0)
                } else {
                    state.copy(inputActionIndex = state.inputActionIndex + 1)
                },
            )
            Gesture.TAP -> when {
                state.inputActionIndex < photoCount -> removePhoto(state, state.inputActionIndex)
                state.inputActionIndex == clearIndex -> HudInteractionDecision(
                    state.copy(
                        showInputStaging = false,
                        stagingText = "",
                        photoThumbnails = emptyList(),
                        inputActionIndex = 0,
                        focusedArea = ChatFocusArea.CONTENT,
                    ),
                    if (photoCount > 0) listOf(HudInteractionAction.RemoveAllPhotos) else emptyList(),
                )
                state.inputActionIndex == sendIndex -> {
                    val text = state.stagingText.trim()
                    val hasContent = text.isNotEmpty() || photoCount > 0
                    HudInteractionDecision(
                        state.copy(
                            inputText = text,
                            showInputStaging = false,
                            stagingText = "",
                            inputActionIndex = 0,
                        ),
                        if (hasContent) listOf(HudInteractionAction.SubmitInput) else emptyList(),
                    )
                }
                else -> HudInteractionDecision(state)
            }
            Gesture.DOUBLE_TAP -> HudInteractionDecision(state.copy(focusedArea = ChatFocusArea.CONTENT))
            Gesture.LONG_PRESS -> HudInteractionDecision(
                state,
                listOf(HudInteractionAction.StartVoice),
            )
        }
    }

    private fun removePhoto(state: ChatHudState, index: Int): HudInteractionDecision {
        val thumbnails = state.photoThumbnails.toMutableList().apply { removeAt(index) }
        val noStagingRemains = thumbnails.isEmpty() && !state.showInputStaging
        return HudInteractionDecision(
            state.copy(
                photoThumbnails = thumbnails,
                inputActionIndex = if (noStagingRemains) 0 else thumbnails.size + 1,
                focusedArea = if (noStagingRemains) ChatFocusArea.MENU else state.focusedArea,
                menuBarIndex = if (noStagingRemains) 0 else state.menuBarIndex,
            ),
            listOf(HudInteractionAction.RemovePhoto(index)),
        )
    }

    private fun planMenu(state: ChatHudState, gesture: Gesture): HudInteractionDecision =
        when (gesture) {
            Gesture.SWIPE_FORWARD -> HudInteractionDecision(
                if (state.menuBarIndex == 0) {
                    if (state.showInputStaging || state.photoThumbnails.isNotEmpty()) {
                        state.copy(
                            focusedArea = ChatFocusArea.INPUT,
                            inputActionIndex = state.photoThumbnails.size + 1,
                        )
                    } else {
                        state.copy(focusedArea = ChatFocusArea.CONTENT)
                    }
                } else {
                    state.copy(menuBarIndex = state.menuBarIndex - 1)
                },
            )
            Gesture.SWIPE_BACKWARD -> HudInteractionDecision(
                state.copy(menuBarIndex = minOf(MenuBarItem.entries.lastIndex, state.menuBarIndex + 1)),
            )
            Gesture.TAP -> HudInteractionDecision(
                state,
                listOf(HudInteractionAction.ExecuteMenuItem(MenuBarItem.entries[state.menuBarIndex])),
            )
            Gesture.DOUBLE_TAP -> HudInteractionDecision(state.copy(showExitConfirm = true))
            Gesture.LONG_PRESS -> HudInteractionDecision(
                state,
                listOf(HudInteractionAction.StartVoice),
            )
        }
}
