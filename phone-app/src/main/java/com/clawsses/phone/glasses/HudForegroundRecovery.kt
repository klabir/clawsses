package com.clawsses.phone.glasses

/** Decides when the phone should reclaim the glasses foreground from Rokid's launcher. */
internal class HudForegroundRecovery(
    private val launcherPackage: String,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private var lastRecoveryAtMs: Long? = null

    fun shouldRecover(packageName: String?, connected: Boolean, nowMs: Long): Boolean {
        if (!connected || packageName != launcherPackage) return false

        val previous = lastRecoveryAtMs
        if (previous != null && nowMs - previous < cooldownMs) return false

        lastRecoveryAtMs = nowMs
        return true
    }

    fun reset() {
        lastRecoveryAtMs = null
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 3_000L
    }
}
