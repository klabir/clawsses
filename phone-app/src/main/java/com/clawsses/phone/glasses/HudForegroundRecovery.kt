package com.clawsses.phone.glasses

/**
 * Grants Clawsses a bounded, one-shot opportunity to reclaim the glasses foreground after it
 * deliberately exits Rokid's AI scene. Launcher visits outside that window belong to the user.
 */
internal class HudForegroundRecovery(
    private val launcherPackage: String,
    private val hudPackage: String,
    private val recoveryWindowMs: Long = DEFAULT_RECOVERY_WINDOW_MS,
) {
    private var armedUntilMs: Long? = null
    private var recoveryScheduled = false

    fun armForAiExit(nowMs: Long) {
        armedUntilMs = nowMs + recoveryWindowMs
    }

    fun scheduleForAiExit(nowMs: Long) {
        armForAiExit(nowMs)
        recoveryScheduled = true
    }

    fun onForegroundChanged(
        packageName: String?,
        connected: Boolean,
        nowMs: Long,
    ): ForegroundAction {
        if (!connected) {
            reset()
            return ForegroundAction.CANCEL_RECOVERY
        }

        if (packageName == hudPackage || packageName != launcherPackage) {
            reset()
            return ForegroundAction.CANCEL_RECOVERY
        }

        if (!isArmed(nowMs)) {
            reset()
            return ForegroundAction.NONE
        }

        if (recoveryScheduled) return ForegroundAction.NONE
        recoveryScheduled = true
        return ForegroundAction.SCHEDULE_RECOVERY
    }

    fun consumeScheduledRecovery(connected: Boolean, nowMs: Long): Boolean {
        val recover = connected && recoveryScheduled && isArmed(nowMs)
        reset()
        return recover
    }

    fun reset() {
        armedUntilMs = null
        recoveryScheduled = false
    }

    private fun isArmed(nowMs: Long): Boolean = armedUntilMs?.let { nowMs <= it } == true

    enum class ForegroundAction {
        NONE,
        SCHEDULE_RECOVERY,
        CANCEL_RECOVERY,
    }

    companion object {
        const val DEFAULT_RECOVERY_WINDOW_MS = 5_000L
    }
}
