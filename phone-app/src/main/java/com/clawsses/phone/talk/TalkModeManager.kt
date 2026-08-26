package com.clawsses.phone.talk

import android.content.Context
import com.clawsses.phone.util.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TalkModePhase {
    OFF,
    IDLE,
    LISTENING,
    TRANSCRIBING,
    SENDING,
    WAITING,
    SPEAKING,
    ABORTING,
    ERROR,
    STANDBY,
    DISCONNECTED,
}

enum class TalkModeSource {
    PHONE,
    GLASSES,
}

enum class TalkInteractionMode {
    /** Activation-gated conversation with a follow-up turn after each spoken answer. */
    FOLLOW_UP,

    /** Continuously restarts recognition while the selected source remains available. */
    ALWAYS_LISTENING,
}

enum class TalkActivation {
    EXPLICIT,
    AUTOMATIC,
}

enum class TalkRestartReason {
    RESPONSE_COMPLETE,
    EMPTY_RESULT,
    RECOGNITION_ERROR,
    COMMAND_COMPLETE,
    STANDBY_RESUME,
    CONNECTION_READY,
}

data class TalkModeState(
    val enabled: Boolean = false,
    val phase: TalkModePhase = TalkModePhase.OFF,
    val cycleId: Long = 0,
    val source: TalkModeSource = TalkModeSource.GLASSES,
    val interactionMode: TalkInteractionMode = TalkModeManager.DEFAULT_INTERACTION_MODE,
    val conversationActive: Boolean = false,
    val error: String? = null,
) {
    val interruptible: Boolean
        get() = phase in setOf(
            TalkModePhase.SENDING,
            TalkModePhase.WAITING,
            TalkModePhase.SPEAKING,
            TalkModePhase.ABORTING,
        )
}

internal object TalkModeTransitions {
    fun setEnabled(state: TalkModeState, enabled: Boolean, source: TalkModeSource): TalkModeState =
        if (enabled) {
            state.copy(enabled = true, phase = TalkModePhase.IDLE, source = source, error = null)
        } else {
            state.copy(
                enabled = false,
                phase = TalkModePhase.OFF,
                source = source,
                conversationActive = false,
                error = null,
            )
        }

    fun setInteractionMode(state: TalkModeState, mode: TalkInteractionMode): TalkModeState =
        state.copy(
            interactionMode = mode,
            conversationActive = false,
            phase = if (state.enabled) TalkModePhase.IDLE else TalkModePhase.OFF,
            cycleId = state.cycleId + 1,
            error = null,
        )

    fun beginListening(
        state: TalkModeState,
        source: TalkModeSource,
        activation: TalkActivation = TalkActivation.EXPLICIT,
    ): TalkModeState {
        if (!state.enabled) return state
        if (state.interactionMode == TalkInteractionMode.FOLLOW_UP &&
            activation == TalkActivation.AUTOMATIC &&
            !state.conversationActive
        ) return state
        return state.copy(
            phase = TalkModePhase.LISTENING,
            cycleId = state.cycleId + 1,
            source = source,
            conversationActive = state.conversationActive || activation == TalkActivation.EXPLICIT,
            error = null,
        )
    }

    fun beginSpeaking(state: TalkModeState): TalkModeState =
        if (!state.enabled || state.phase == TalkModePhase.SPEAKING) state else state.copy(
            phase = TalkModePhase.SPEAKING,
            cycleId = state.cycleId + 1,
            error = null,
        )

    fun setPhase(
        state: TalkModeState,
        phase: TalkModePhase,
        cycleId: Long? = null,
        error: String? = null,
    ): TalkModeState {
        if (!state.enabled) return state.copy(phase = TalkModePhase.OFF, error = null)
        if (cycleId != null && cycleId != state.cycleId) return state
        return state.copy(phase = phase, error = error)
    }

    fun pauseForStandby(state: TalkModeState): TalkModeState =
        if (!state.enabled || state.source != TalkModeSource.GLASSES) state else state.copy(
            phase = TalkModePhase.STANDBY,
            cycleId = state.cycleId + 1,
            conversationActive = state.interactionMode == TalkInteractionMode.ALWAYS_LISTENING,
            error = null,
        )

    fun endConversation(state: TalkModeState): TalkModeState =
        if (!state.enabled) state else state.copy(
            phase = TalkModePhase.IDLE,
            cycleId = state.cycleId + 1,
            conversationActive = false,
            error = null,
        )

    fun markReady(state: TalkModeState): TalkModeState =
        if (!state.enabled) state else state.copy(phase = TalkModePhase.IDLE, error = null)

    fun shouldRestart(state: TalkModeState, reason: TalkRestartReason): Boolean {
        if (!state.enabled) return false
        return when (state.interactionMode) {
            TalkInteractionMode.ALWAYS_LISTENING -> true
            TalkInteractionMode.FOLLOW_UP ->
                state.conversationActive && reason == TalkRestartReason.RESPONSE_COMPLETE
        }
    }

    fun shouldPauseForStandby(state: TalkModeState, glassesAwake: Boolean): Boolean =
        state.enabled &&
            state.source == TalkModeSource.GLASSES &&
            !glassesAwake &&
            state.phase in setOf(
                TalkModePhase.IDLE,
                TalkModePhase.LISTENING,
                TalkModePhase.TRANSCRIBING,
                TalkModePhase.ERROR,
            )

    fun shouldResumeFromStandby(state: TalkModeState, glassesAwake: Boolean): Boolean =
        state.enabled &&
            state.source == TalkModeSource.GLASSES &&
            glassesAwake &&
            state.phase == TalkModePhase.STANDBY &&
            state.interactionMode == TalkInteractionMode.ALWAYS_LISTENING
}

/** Persistent Talk Mode state. Only the preference is durable; active cycles never are. */
class TalkModeManager(context: Context) {
    private val prefs = SecurePreferences.create(context, PREFS_NAME)
    private val savedSource = runCatching {
        TalkModeSource.valueOf(prefs.getString(KEY_SOURCE, TalkModeSource.GLASSES.name)!!)
    }.getOrDefault(TalkModeSource.GLASSES)
    private val savedEnabled = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    private val savedInteractionMode = runCatching {
        TalkInteractionMode.valueOf(
            prefs.getString(KEY_INTERACTION_MODE, DEFAULT_INTERACTION_MODE.name)!!,
        )
    }.getOrDefault(DEFAULT_INTERACTION_MODE)

    private val _state = MutableStateFlow(
        TalkModeState(
            enabled = savedEnabled,
            phase = if (savedEnabled) TalkModePhase.DISCONNECTED else TalkModePhase.OFF,
            source = savedSource,
            interactionMode = savedInteractionMode,
        )
    )
    val state: StateFlow<TalkModeState> = _state.asStateFlow()

    fun setEnabled(enabled: Boolean, source: TalkModeSource = _state.value.source) {
        _state.value = TalkModeTransitions.setEnabled(_state.value, enabled, source)
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_SOURCE, source.name)
            .apply()
    }

    fun setInteractionMode(mode: TalkInteractionMode) {
        _state.value = TalkModeTransitions.setInteractionMode(_state.value, mode)
        prefs.edit().putString(KEY_INTERACTION_MODE, mode.name).apply()
    }

    fun beginListening(
        source: TalkModeSource = _state.value.source,
        activation: TalkActivation = TalkActivation.EXPLICIT,
    ): Long {
        val sourceChanged = source != _state.value.source
        _state.value = TalkModeTransitions.beginListening(_state.value, source, activation)
        if (sourceChanged && _state.value.enabled) {
            prefs.edit().putString(KEY_SOURCE, source.name).apply()
        }
        return _state.value.cycleId
    }

    fun beginSpeaking() {
        _state.value = TalkModeTransitions.beginSpeaking(_state.value)
    }

    fun setPhase(phase: TalkModePhase, cycleId: Long? = null, error: String? = null) {
        _state.value = TalkModeTransitions.setPhase(_state.value, phase, cycleId, error)
    }

    fun resetToIdle() = setPhase(TalkModePhase.IDLE)

    fun endConversation() {
        _state.value = TalkModeTransitions.endConversation(_state.value)
    }

    fun markReady() {
        _state.value = TalkModeTransitions.markReady(_state.value)
    }

    fun pauseForStandby() {
        _state.value = TalkModeTransitions.pauseForStandby(_state.value)
    }

    companion object {
        internal const val DEFAULT_ENABLED = true
        internal val DEFAULT_INTERACTION_MODE = TalkInteractionMode.FOLLOW_UP
        internal const val FOLLOW_UP_WINDOW_MS = 12_000L
        private const val PREFS_NAME = "clawsses"
        private const val KEY_ENABLED = "talk_mode_enabled"
        private const val KEY_SOURCE = "talk_mode_source"
        private const val KEY_INTERACTION_MODE = "talk_interaction_mode"
    }
}
