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

    /** Schedules recovery only when a matching accepted AI activation armed this cycle. */
    fun scheduleIfArmedForAiExit(nowMs: Long): Boolean {
        if (!isArmed(nowMs) || recoveryScheduled) return false
        armedUntilMs = nowMs + recoveryWindowMs
        recoveryScheduled = true
        return true
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

        if (packageName == hudPackage) {
            // The vendor can report the HUD once more while its AI scene is starting. Preserve an
            // armed cycle until exit; only a scheduled recovery followed by HUD resume proves that
            // recovery completed.
            if (!recoveryScheduled) return ForegroundAction.NONE
            reset()
            return ForegroundAction.CANCEL_RECOVERY
        }

        if (packageName != launcherPackage) {
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
