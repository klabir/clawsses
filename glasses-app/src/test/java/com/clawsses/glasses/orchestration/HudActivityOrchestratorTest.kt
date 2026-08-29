package com.clawsses.glasses.orchestration

import android.view.KeyEvent
import com.clawsses.glasses.input.GestureHandler.Gesture
import com.clawsses.glasses.ui.ChatFocusArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudActivityOrchestratorTest {
    private fun context(
        hasHudCards: Boolean = false,
        liveCaptionEnabled: Boolean = false,
        showExitConfirm: Boolean = false,
        showSlashMenu: Boolean = false,
        showMoreMenu: Boolean = false,
        showSessionPicker: Boolean = false,
        showAgentPicker: Boolean = false,
        showModelPicker: Boolean = false,
        voiceActive: Boolean = false,
        focusedArea: ChatFocusArea = ChatFocusArea.CONTENT,
    ) = HudGestureContext(
        hasHudCards,
        liveCaptionEnabled,
        showExitConfirm,
        showSlashMenu,
        showMoreMenu,
        showSessionPicker,
        showAgentPicker,
        showModelPicker,
        voiceActive,
        focusedArea,
    )

    @Test
    fun `modal gestures use the established precedence`() {
        assertEquals(
            HudGestureTarget.HUD_CARD,
            HudGestureRouter.route(
                context(hasHudCards = true, showExitConfirm = true),
                Gesture.TAP,
            ),
        )
        assertEquals(
            HudGestureTarget.EXIT_CONFIRM,
            HudGestureRouter.route(
                context(showExitConfirm = true, showMoreMenu = true),
                Gesture.TAP,
            ),
        )
        assertEquals(
            HudGestureTarget.MODEL_PICKER,
            HudGestureRouter.route(context(showModelPicker = true), Gesture.TAP),
        )
    }

    @Test
    fun `live caption dismissal only intercepts double tap`() {
        val caption = context(liveCaptionEnabled = true, focusedArea = ChatFocusArea.INPUT)

        assertEquals(
            HudGestureTarget.DISMISS_LIVE_CAPTION,
            HudGestureRouter.route(caption, Gesture.DOUBLE_TAP),
        )
        assertEquals(HudGestureTarget.INPUT, HudGestureRouter.route(caption, Gesture.TAP))
    }

    @Test
    fun `voice cancellation follows overlays and precedes focused area`() {
        assertEquals(
            HudGestureTarget.CANCEL_VOICE,
            HudGestureRouter.route(context(voiceActive = true), Gesture.TAP),
        )
        assertEquals(
            HudGestureTarget.MORE_MENU,
            HudGestureRouter.route(
                context(showMoreMenu = true, voiceActive = true),
                Gesture.TAP,
            ),
        )
    }

    @Test
    fun `hardware keys normalize to gestures and repeats are consumed`() {
        assertEquals(
            Gesture.SWIPE_FORWARD,
            HudKeyRouter.route(KeyEvent.KEYCODE_VOLUME_UP, repeatCount = 0).gesture,
        )
        assertEquals(
            Gesture.DOUBLE_TAP,
            HudKeyRouter.route(KeyEvent.KEYCODE_BACK, repeatCount = 0).gesture,
        )
        assertTrue(HudKeyRouter.route(KeyEvent.KEYCODE_A, repeatCount = 2).consumed)
        assertNull(HudKeyRouter.route(KeyEvent.KEYCODE_A, repeatCount = 0).gesture)
    }

    @Test
    fun `only a disconnected to connected transition requests state`() {
        val connected = HudLifecycleRouter.connectionTransition(false, true)
        val unchanged = HudLifecycleRouter.connectionTransition(true, true)
        val disconnected = HudLifecycleRouter.connectionTransition(true, false)

        assertTrue(connected.stateChanged)
        assertTrue(connected.requestPhoneState)
        assertFalse(unchanged.stateChanged)
        assertFalse(unchanged.requestPhoneState)
        assertTrue(disconnected.stateChanged)
        assertFalse(disconnected.requestPhoneState)
    }
}
