package com.clawsses.phone.glasses

/**
 * Keeps asynchronous Rokid hotspot callbacks scoped to the installation attempt that created them.
 * The CXR SDK may deliver duplicate or late hotspot advertisements; neither may replace an active
 * Android network request.
 */
internal class HotspotAttemptGate {
    enum class AdvertisementDecision {
        START_CONNECTION,
        IGNORE_DUPLICATE,
        IGNORE_STALE,
    }

    private var nextAttemptId = 0L
    private var activeAttemptId: Long? = null
    private var activeEndpointKey: String? = null

    @Synchronized
    fun begin(): Long {
        val attemptId = ++nextAttemptId
        activeAttemptId = attemptId
        activeEndpointKey = null
        return attemptId
    }

    @Synchronized
    fun registerAdvertisement(attemptId: Long, ssid: String, ipAddress: String): AdvertisementDecision {
        if (activeAttemptId != attemptId) return AdvertisementDecision.IGNORE_STALE

        val endpointKey = "$ssid\u0000$ipAddress"
        if (activeEndpointKey == endpointKey) return AdvertisementDecision.IGNORE_DUPLICATE

        activeEndpointKey = endpointKey
        return AdvertisementDecision.START_CONNECTION
    }

    @Synchronized
    fun isActive(attemptId: Long): Boolean = activeAttemptId == attemptId

    @Synchronized
    fun end(attemptId: Long): Boolean {
        if (activeAttemptId != attemptId) return false
        activeAttemptId = null
        activeEndpointKey = null
        return true
    }
}
