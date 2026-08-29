package com.clawsses.phone.voice

/** Prevents immediate CXR-audio reuse after that transport disconnects mid-capture. */
internal class DirectAudioCircuitBreaker(
    private val stableReconnectMs: Long = DEFAULT_STABLE_RECONNECT_MS,
) {
    private var awaitingReconnect = false
    private var blockedUntilMs = 0L

    @Synchronized
    fun onDisconnect(duringDirectAttempt: Boolean) {
        if (!duringDirectAttempt) return
        awaitingReconnect = true
        blockedUntilMs = Long.MAX_VALUE
    }

    @Synchronized
    fun onConnected(nowMs: Long) {
        if (!awaitingReconnect) return
        awaitingReconnect = false
        blockedUntilMs = nowMs + stableReconnectMs
    }

    @Synchronized
    fun canStart(nowMs: Long): Boolean = !awaitingReconnect && nowMs >= blockedUntilMs

    companion object {
        const val DEFAULT_STABLE_RECONNECT_MS = 5_000L
    }
}

internal fun directCaptureNeedsPhoneFallback(
    usedDirectGlassesAudio: Boolean,
    finalText: String,
): Boolean = usedDirectGlassesAudio && finalText.isBlank()
