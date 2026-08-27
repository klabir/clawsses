package com.clawsses.phone.service

enum class WakeLockReason {
    VOICE_RECOGNITION,
    APK_TRANSFER,
    RECONNECT,
}

/** Tracks bounded CPU wake-lock leases without depending on Android framework classes. */
class WakeLockLeaseRegistry {
    private val expirations = mutableMapOf<WakeLockReason, Long>()

    fun acquire(reason: WakeLockReason, nowMs: Long, durationMs: Long): Long? {
        require(durationMs > 0L)
        expirations[reason] = nowMs + durationMs
        return nextExpiration(nowMs)
    }

    fun release(reason: WakeLockReason, nowMs: Long): Long? {
        expirations.remove(reason)
        return nextExpiration(nowMs)
    }

    fun nextExpiration(nowMs: Long): Long? {
        expirations.entries.removeAll { it.value <= nowMs }
        return expirations.values.maxOrNull()
    }

    fun clear() = expirations.clear()
}
