package com.clawsses.phone.runtime

/**
 * Coalesces one physical glasses activation that arrives through both the HUD command bridge and
 * the CXR vendor callback. This gate intentionally lives above both transports so neither source
 * can start a second audio owner for the same gesture.
 */
internal class GlassesVoiceActivationGate(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private var lastAcceptedAtMs: Long? = null

    @Synchronized
    fun tryAccept(nowMs: Long): Boolean {
        val previous = lastAcceptedAtMs
        if (previous != null && nowMs - previous < cooldownMs) return false
        lastAcceptedAtMs = nowMs
        return true
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 1_500L
    }
}
