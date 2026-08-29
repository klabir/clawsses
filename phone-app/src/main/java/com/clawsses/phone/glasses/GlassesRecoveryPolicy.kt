package com.clawsses.phone.glasses

enum class GlassesRecoveryStatus {
    RESPONSIVE,
    STANDBY,
    RECONNECTING,
    DEEP_SLEEP_SUSPECTED,
}

data class GlassesRecoverySnapshot(
    val status: GlassesRecoveryStatus = GlassesRecoveryStatus.STANDBY,
    val reconnectAttempt: Int = 0,
    val consecutiveWakeTimeouts: Int = 0,
    val deepSleepDetections: Int = 0,
    val successfulRecoveries: Int = 0,
    val reconnectTimeouts: Int = 0,
) {
    val needsPhysicalWake: Boolean
        get() = status == GlassesRecoveryStatus.DEEP_SLEEP_SUSPECTED
}

/**
 * Pure policy for classifying a bonded Rokid device that no longer answers CXR wake/discovery.
 * It never mutates Bluetooth state; callers decide when to schedule or stop SDK reconnects.
 */
class GlassesRecoveryTracker(
    private val deepSleepAttemptThreshold: Int = 3,
    private val maxAutomaticReconnectAttempts: Int = 5,
    private val wakeTimeoutThreshold: Int = 1,
) {
    init {
        require(deepSleepAttemptThreshold > 0)
        require(maxAutomaticReconnectAttempts >= deepSleepAttemptThreshold)
        require(wakeTimeoutThreshold > 0)
    }

    private var snapshot = GlassesRecoverySnapshot()

    fun current(): GlassesRecoverySnapshot = snapshot

    fun onConnected(): GlassesRecoverySnapshot {
        val recovered = snapshot.needsPhysicalWake || snapshot.reconnectAttempt > 0
        snapshot = snapshot.copy(
            status = GlassesRecoveryStatus.RESPONSIVE,
            reconnectAttempt = 0,
            consecutiveWakeTimeouts = 0,
            successfulRecoveries = snapshot.successfulRecoveries + if (recovered) 1 else 0,
        )
        return snapshot
    }

    fun onResponsiveActivity(): GlassesRecoverySnapshot {
        val recovered = snapshot.needsPhysicalWake
        snapshot = snapshot.copy(
            status = GlassesRecoveryStatus.RESPONSIVE,
            reconnectAttempt = 0,
            consecutiveWakeTimeouts = 0,
            successfulRecoveries = snapshot.successfulRecoveries + if (recovered) 1 else 0,
        )
        return snapshot
    }

    fun onStandbyDetected(): GlassesRecoverySnapshot {
        if (!snapshot.needsPhysicalWake) {
            snapshot = snapshot.copy(status = GlassesRecoveryStatus.STANDBY)
        }
        return snapshot
    }

    fun onWakeTimeout(): GlassesRecoverySnapshot {
        val timeouts = snapshot.consecutiveWakeTimeouts + 1
        snapshot = snapshot.copy(
            status = if (timeouts >= wakeTimeoutThreshold) {
                GlassesRecoveryStatus.DEEP_SLEEP_SUSPECTED
            } else {
                GlassesRecoveryStatus.STANDBY
            },
            consecutiveWakeTimeouts = timeouts,
            deepSleepDetections = snapshot.deepSleepDetections +
                if (timeouts == wakeTimeoutThreshold) 1 else 0,
        )
        return snapshot
    }

    /** Returns whether another automatic reconnect attempt is still within budget. */
    fun onReconnectAttempt(attempt: Int): Boolean {
        require(attempt > 0)
        val firstDeepSleepClassification =
            attempt >= deepSleepAttemptThreshold && !snapshot.needsPhysicalWake
        snapshot = snapshot.copy(
            status = if (attempt >= deepSleepAttemptThreshold) {
                GlassesRecoveryStatus.DEEP_SLEEP_SUSPECTED
            } else {
                GlassesRecoveryStatus.RECONNECTING
            },
            reconnectAttempt = attempt,
            deepSleepDetections = snapshot.deepSleepDetections +
                if (firstDeepSleepClassification) 1 else 0,
        )
        return attempt <= maxAutomaticReconnectAttempts
    }

    fun onReconnectTimeout(): GlassesRecoverySnapshot {
        snapshot = snapshot.copy(reconnectTimeouts = snapshot.reconnectTimeouts + 1)
        return snapshot
    }

    fun onManualRetry(): GlassesRecoverySnapshot {
        snapshot = snapshot.copy(
            status = GlassesRecoveryStatus.RECONNECTING,
            reconnectAttempt = 0,
            consecutiveWakeTimeouts = 0,
        )
        return snapshot
    }
}
