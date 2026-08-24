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

data class TalkModeState(
    val enabled: Boolean = false,
    val phase: TalkModePhase = TalkModePhase.OFF,
    val cycleId: Long = 0,
    val source: TalkModeSource = TalkModeSource.GLASSES,
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
            state.copy(enabled = false, phase = TalkModePhase.OFF, source = source, error = null)
        }

    fun beginListening(state: TalkModeState, source: TalkModeSource): TalkModeState =
        if (!state.enabled) state else state.copy(
            phase = TalkModePhase.LISTENING,
            cycleId = state.cycleId + 1,
            source = source,
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
            error = null,
        )

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
            state.phase == TalkModePhase.STANDBY
}

/** Persistent Talk Mode state. Only the preference is durable; active cycles never are. */
class TalkModeManager(context: Context) {
    private val prefs = SecurePreferences.create(context, PREFS_NAME)
    private val savedSource = runCatching {
        TalkModeSource.valueOf(prefs.getString(KEY_SOURCE, TalkModeSource.GLASSES.name)!!)
    }.getOrDefault(TalkModeSource.GLASSES)
    private val savedEnabled = prefs.getBoolean(KEY_ENABLED, false)

    private val _state = MutableStateFlow(
        TalkModeState(
            enabled = savedEnabled,
            phase = if (savedEnabled) TalkModePhase.DISCONNECTED else TalkModePhase.OFF,
            source = savedSource,
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

    fun beginListening(source: TalkModeSource = _state.value.source): Long {
        val sourceChanged = source != _state.value.source
        _state.value = TalkModeTransitions.beginListening(_state.value, source)
        if (sourceChanged && _state.value.enabled) {
            prefs.edit().putString(KEY_SOURCE, source.name).apply()
        }
        return _state.value.cycleId
    }

    fun setPhase(phase: TalkModePhase, cycleId: Long? = null, error: String? = null) {
        _state.value = TalkModeTransitions.setPhase(_state.value, phase, cycleId, error)
    }

    fun resetToIdle() = setPhase(TalkModePhase.IDLE)

    fun pauseForStandby() {
        _state.value = TalkModeTransitions.pauseForStandby(_state.value)
    }

    companion object {
        private const val PREFS_NAME = "clawsses"
        private const val KEY_ENABLED = "talk_mode_enabled"
        private const val KEY_SOURCE = "talk_mode_source"
    }
}
