package com.clawsses.phone.openclaw

import com.clawsses.shared.OpenClawResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Owns mutable WebSocket/auth state while [OpenClawClient] remains the public facade. */
internal class OpenClawTransportAuthComponent(
    networkInitiallyAvailable: Boolean,
    reconnectBaseDelayMs: Long,
    reconnectMaxDelayMs: Long,
) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    val requestSeq = AtomicLong(1)
    val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<OpenClawResponse>>()
    val connectionLock = Any()
    val connectionEpoch = ConnectionEpoch()
    val reconnectBackoff = ReconnectBackoff(reconnectBaseDelayMs, reconnectMaxDelayMs)
    val networkAvailability = NetworkAvailabilityGate(networkInitiallyAvailable)

    var webSocket: WebSocket? = null
    var host: String = ""
    var port: Int = 18789
    var token: String = ""
    var shouldReconnect = false
    var reconnectJob: Job? = null
    var challengeNonce: String? = null
}

internal class ReconnectBackoff(
    private val baseDelayMs: Long,
    private val maxDelayMs: Long,
    private val randomUnit: () -> Double = { Random.nextDouble() },
) {
    private var attempt = 0

    fun nextDelayMs(): Long {
        val exponent = attempt.coerceAtMost(30)
        attempt++
        val exponential = if (exponent >= 30) {
            maxDelayMs
        } else {
            (baseDelayMs * (1L shl exponent)).coerceAtMost(maxDelayMs)
        }
        val jitterMultiplier = 0.8 + randomUnit().coerceIn(0.0, 1.0) * 0.4
        return (exponential * jitterMultiplier).toLong().coerceIn(1L, maxDelayMs)
    }

    fun reset() {
        attempt = 0
    }
}

internal class ConnectionEpoch {
    private var current = 0L
    private var ended: Long? = null

    fun begin(): Long {
        current++
        ended = null
        return current
    }

    fun invalidate() {
        current++
        ended = null
    }

    fun isCurrent(generation: Long): Boolean = generation == current

    fun isEnded(generation: Long): Boolean = generation == ended

    fun markEnded(generation: Long): Boolean {
        if (!isCurrent(generation) || isEnded(generation)) return false
        ended = generation
        return true
    }
}

internal enum class NetworkAvailabilityChange {
    UNCHANGED,
    LOST,
    RESTORED,
}

internal class NetworkAvailabilityGate(initiallyAvailable: Boolean) {
    @Volatile private var available = initiallyAvailable

    fun isAvailable(): Boolean = available

    @Synchronized
    fun update(newValue: Boolean): NetworkAvailabilityChange {
        if (available == newValue) return NetworkAvailabilityChange.UNCHANGED
        available = newValue
        return if (newValue) NetworkAvailabilityChange.RESTORED else NetworkAvailabilityChange.LOST
    }
}
