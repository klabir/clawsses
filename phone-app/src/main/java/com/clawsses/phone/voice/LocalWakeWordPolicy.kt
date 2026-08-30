package com.clawsses.phone.voice

enum class LocalWakeWordPhase {
    DISABLED,
    PAUSED,
    STARTING,
    LISTENING,
    ACTIVATING,
    ERROR,
}

data class LocalWakeWordStatus(
    val enabled: Boolean = false,
    val phase: LocalWakeWordPhase = LocalWakeWordPhase.DISABLED,
    val keyword: String = "HEY CLAWSSES",
    val detectionCount: Long = 0L,
    val lastDetectedAtMs: Long? = null,
    val error: String? = null,
)

internal data class LocalWakeWordBlockers(
    val foregroundCaptureActive: Boolean,
    val talkModeActive: Boolean,
    val liveCaptionsActive: Boolean,
    val playbackActive: Boolean,
    val openClawRunActive: Boolean,
    val activationActive: Boolean,
)

internal fun shouldRunLocalWakeWord(
    enabled: Boolean,
    faulted: Boolean,
    blockers: LocalWakeWordBlockers,
): Boolean = enabled && !faulted && !blockers.foregroundCaptureActive &&
    !blockers.talkModeActive && !blockers.liveCaptionsActive &&
    !blockers.playbackActive && !blockers.openClawRunActive && !blockers.activationActive
