package com.clawsses.glasses.orchestration

import android.view.KeyEvent
import com.clawsses.glasses.input.GestureHandler.Gesture
import com.clawsses.glasses.ui.ChatFocusArea

data class HudGestureContext(
    val hasHudCards: Boolean,
    val liveCaptionEnabled: Boolean,
    val showExitConfirm: Boolean,
    val showSlashMenu: Boolean,
    val showMoreMenu: Boolean,
    val showSessionPicker: Boolean,
    val showAgentPicker: Boolean,
    val showModelPicker: Boolean,
    val voiceActive: Boolean,
    val focusedArea: ChatFocusArea,
)

enum class HudGestureTarget {
    HUD_CARD,
    DISMISS_LIVE_CAPTION,
    EXIT_CONFIRM,
    SLASH_MENU,
    MORE_MENU,
    SESSION_PICKER,
    AGENT_PICKER,
    MODEL_PICKER,
    CANCEL_VOICE,
    CONTENT,
    INPUT,
    MENU,
}

/** Resolves gesture precedence without performing UI, SDK, or transport side effects. */
object HudGestureRouter {
    fun route(context: HudGestureContext, gesture: Gesture): HudGestureTarget = when {
        context.hasHudCards -> HudGestureTarget.HUD_CARD
        context.liveCaptionEnabled && gesture == Gesture.DOUBLE_TAP ->
            HudGestureTarget.DISMISS_LIVE_CAPTION
        context.showExitConfirm -> HudGestureTarget.EXIT_CONFIRM
        context.showSlashMenu -> HudGestureTarget.SLASH_MENU
        context.showMoreMenu -> HudGestureTarget.MORE_MENU
        context.showSessionPicker -> HudGestureTarget.SESSION_PICKER
        context.showAgentPicker -> HudGestureTarget.AGENT_PICKER
        context.showModelPicker -> HudGestureTarget.MODEL_PICKER
        context.voiceActive && gesture == Gesture.TAP -> HudGestureTarget.CANCEL_VOICE
        context.focusedArea == ChatFocusArea.CONTENT -> HudGestureTarget.CONTENT
        context.focusedArea == ChatFocusArea.INPUT -> HudGestureTarget.INPUT
        else -> HudGestureTarget.MENU
    }
}

data class HudKeyDecision(
    val gesture: Gesture? = null,
    val consumed: Boolean = false,
)

/** Normalizes emulator and glasses hardware keys before the Activity handles a gesture. */
object HudKeyRouter {
    fun route(keyCode: Int, repeatCount: Int): HudKeyDecision {
        if (repeatCount > 0) return HudKeyDecision(consumed = true)
        val gesture = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_VOLUME_UP,
            -> Gesture.SWIPE_FORWARD
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            -> Gesture.SWIPE_BACKWARD
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> Gesture.TAP
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_M,
            KeyEvent.KEYCODE_DEL,
            -> Gesture.DOUBLE_TAP
            else -> null
        }
        return HudKeyDecision(gesture = gesture, consumed = gesture != null)
    }
}

data class HudConnectionTransition(
    val connected: Boolean,
    val stateChanged: Boolean,
    val requestPhoneState: Boolean,
)

/** Decides whether a process-scoped bridge transition needs visible state and a resync. */
object HudLifecycleRouter {
    fun connectionTransition(
        currentlyConnected: Boolean,
        bridgeConnected: Boolean,
    ) = HudConnectionTransition(
        connected = bridgeConnected,
        stateChanged = currentlyConnected != bridgeConnected,
        requestPhoneState = !currentlyConnected && bridgeConnected,
    )
}
