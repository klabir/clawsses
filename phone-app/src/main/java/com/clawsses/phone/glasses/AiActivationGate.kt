package com.clawsses.phone.glasses

/** Coalesces the duplicate AI-key and scene-edge callbacks emitted for one activation. */
internal class AiActivationGate(
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
        const val DEFAULT_COOLDOWN_MS = 750L
    }
}
