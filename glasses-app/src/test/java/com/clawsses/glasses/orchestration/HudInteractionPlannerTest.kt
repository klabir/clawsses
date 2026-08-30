package com.clawsses.glasses.orchestration

import com.clawsses.glasses.input.GestureHandler.Gesture
import com.clawsses.glasses.media.ThumbnailHandle
import com.clawsses.glasses.ui.ChatFocusArea
import com.clawsses.glasses.ui.ChatHudState
import com.clawsses.glasses.ui.MenuBarItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudInteractionPlannerTest {
    @Test
    fun `content push-through focuses staged send action`() {
        val decision = HudInteractionPlanner.plan(
            ChatHudState(
                pageIndex = 1,
                pageCount = 2,
                isScrolledToEnd = true,
                showInputStaging = true,
                photoThumbnails = listOf(ThumbnailHandle("photo")),
            ),
            HudGestureTarget.CONTENT,
            Gesture.SWIPE_BACKWARD,
        )

        assertEquals(ChatFocusArea.INPUT, decision.state.focusedArea)
        assertEquals(2, decision.state.inputActionIndex)
        assertTrue(decision.actions.isEmpty())
    }

    @Test
    fun `content swipe requests scrolling before end`() {
        val state = ChatHudState(pageIndex = 0, pageCount = 2, isScrolledToEnd = false)
        val decision = HudInteractionPlanner.plan(
            state,
            HudGestureTarget.CONTENT,
            Gesture.SWIPE_BACKWARD,
        )

        assertEquals(state, decision.state)
        assertEquals(listOf(HudInteractionAction.ScrollDown), decision.actions)
    }

    @Test
    fun `photo tap removes selected thumbnail and emits phone action`() {
        val state = ChatHudState(
            focusedArea = ChatFocusArea.INPUT,
            showInputStaging = true,
            photoThumbnails = listOf(ThumbnailHandle("first"), ThumbnailHandle("second")),
            inputActionIndex = 0,
        )
        val decision = HudInteractionPlanner.plan(state, HudGestureTarget.INPUT, Gesture.TAP)

        assertEquals(listOf("second"), decision.state.photoThumbnails.map { it.key })
        assertEquals(2, decision.state.inputActionIndex)
        assertEquals(listOf(HudInteractionAction.RemovePhoto(0)), decision.actions)
    }

    @Test
    fun `clear dismisses input and removes all phone photos`() {
        val decision = HudInteractionPlanner.plan(
            ChatHudState(
                focusedArea = ChatFocusArea.INPUT,
                photoThumbnails = listOf(ThumbnailHandle("photo")),
                inputActionIndex = 1,
            ),
            HudGestureTarget.INPUT,
            Gesture.TAP,
        )

        assertEquals(ChatFocusArea.CONTENT, decision.state.focusedArea)
        assertTrue(decision.state.photoThumbnails.isEmpty())
        assertEquals(listOf(HudInteractionAction.RemoveAllPhotos), decision.actions)
    }

    @Test
    fun `menu navigation clamps and tap executes selected item`() {
        val state = ChatHudState(
            focusedArea = ChatFocusArea.MENU,
            menuBarIndex = MenuBarItem.entries.lastIndex,
        )
        val clamped = HudInteractionPlanner.plan(
            state,
            HudGestureTarget.MENU,
            Gesture.SWIPE_BACKWARD,
        )
        val tapped = HudInteractionPlanner.plan(clamped.state, HudGestureTarget.MENU, Gesture.TAP)

        assertEquals(MenuBarItem.entries.lastIndex, clamped.state.menuBarIndex)
        assertEquals(
            listOf(HudInteractionAction.ExecuteMenuItem(MenuBarItem.MORE)),
            tapped.actions,
        )
    }

    @Test
    fun `content gestures emit bounded actions and focus menu`() {
        val state = ChatHudState(pageCount = 1, isScrolledToEnd = true)

        assertEquals(
            listOf(HudInteractionAction.ScrollUp),
            HudInteractionPlanner.plan(state, HudGestureTarget.CONTENT, Gesture.SWIPE_FORWARD).actions,
        )
        assertEquals(
            listOf(HudInteractionAction.ScrollToBottom),
            HudInteractionPlanner.plan(state, HudGestureTarget.CONTENT, Gesture.TAP).actions,
        )
        assertEquals(
            listOf(HudInteractionAction.StartVoice),
            HudInteractionPlanner.plan(state, HudGestureTarget.CONTENT, Gesture.LONG_PRESS).actions,
        )
        assertEquals(
            ChatFocusArea.MENU,
            HudInteractionPlanner.plan(state, HudGestureTarget.CONTENT, Gesture.DOUBLE_TAP)
                .state.focusedArea,
        )
    }

    @Test
    fun `input gestures navigate submit and start voice`() {
        val staged = ChatHudState(
            focusedArea = ChatFocusArea.INPUT,
            showInputStaging = true,
            stagingText = "  hello  ",
            inputActionIndex = 1,
        )

        val submitted = HudInteractionPlanner.plan(staged, HudGestureTarget.INPUT, Gesture.TAP)
        assertEquals("hello", submitted.state.inputText)
        assertEquals(listOf(HudInteractionAction.SubmitInput), submitted.actions)
        assertEquals(
            ChatFocusArea.CONTENT,
            HudInteractionPlanner.plan(
                staged.copy(inputActionIndex = 0),
                HudGestureTarget.INPUT,
                Gesture.SWIPE_FORWARD,
            ).state.focusedArea,
        )
        assertEquals(
            ChatFocusArea.MENU,
            HudInteractionPlanner.plan(staged, HudGestureTarget.INPUT, Gesture.SWIPE_BACKWARD)
                .state.focusedArea,
        )
        assertEquals(
            ChatFocusArea.CONTENT,
            HudInteractionPlanner.plan(staged, HudGestureTarget.INPUT, Gesture.DOUBLE_TAP)
                .state.focusedArea,
        )
        assertEquals(
            listOf(HudInteractionAction.StartVoice),
            HudInteractionPlanner.plan(staged, HudGestureTarget.INPUT, Gesture.LONG_PRESS).actions,
        )
    }

    @Test
    fun `menu gestures traverse focus show exit and start voice`() {
        val menu = ChatHudState(focusedArea = ChatFocusArea.MENU, menuBarIndex = 1)
        assertEquals(
            0,
            HudInteractionPlanner.plan(menu, HudGestureTarget.MENU, Gesture.SWIPE_FORWARD)
                .state.menuBarIndex,
        )
        assertEquals(
            ChatFocusArea.CONTENT,
            HudInteractionPlanner.plan(
                menu.copy(menuBarIndex = 0),
                HudGestureTarget.MENU,
                Gesture.SWIPE_FORWARD,
            ).state.focusedArea,
        )
        assertTrue(
            HudInteractionPlanner.plan(menu, HudGestureTarget.MENU, Gesture.DOUBLE_TAP)
                .state.showExitConfirm,
        )
        assertEquals(
            listOf(HudInteractionAction.StartVoice),
            HudInteractionPlanner.plan(menu, HudGestureTarget.MENU, Gesture.LONG_PRESS).actions,
        )
    }
}
