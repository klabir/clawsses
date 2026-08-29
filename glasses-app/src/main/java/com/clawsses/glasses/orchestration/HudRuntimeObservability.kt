package com.clawsses.glasses.orchestration

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Content-free HUD runtime counters safe to emit in production diagnostics. */
class HudRuntimeMetrics {
    private val commands = AtomicLong()
    private val gestures = AtomicLong()
    private val phoneMessages = AtomicLong()
    private val malformedMessages = AtomicLong()
    private val duplicateTransactions = AtomicLong()
    private val reconnectStateRequests = AtomicLong()
    private val streamPublications = AtomicLong()

    fun recordCommand() = commands.incrementAndGet()
    fun recordGesture() = gestures.incrementAndGet()
    fun recordPhoneMessage() = phoneMessages.incrementAndGet()
    fun recordMalformedMessage() = malformedMessages.incrementAndGet()
    fun recordDuplicateTransaction() = duplicateTransactions.incrementAndGet()
    fun recordReconnectStateRequest() = reconnectStateRequests.incrementAndGet()
    fun recordStreamPublication() = streamPublications.incrementAndGet()

    fun snapshot() = HudRuntimeSnapshot(
        commands = commands.get(),
        gestures = gestures.get(),
        phoneMessages = phoneMessages.get(),
        malformedMessages = malformedMessages.get(),
        duplicateTransactions = duplicateTransactions.get(),
        reconnectStateRequests = reconnectStateRequests.get(),
        streamPublications = streamPublications.get(),
    )
}

data class HudRuntimeSnapshot(
    val commands: Long,
    val gestures: Long,
    val phoneMessages: Long,
    val malformedMessages: Long,
    val duplicateTransactions: Long,
    val reconnectStateRequests: Long,
    val streamPublications: Long,
) {
    fun toLogLine(): String =
        "HUD runtime commands=$commands gestures=$gestures phoneMessages=$phoneMessages " +
            "malformed=$malformedMessages duplicateTx=$duplicateTransactions " +
            "reconnectStateRequests=$reconnectStateRequests streamPublications=$streamPublications"
}

/** Retains only the newest transaction IDs needed to ACK replayed transport packets. */
class HudTransportTransactionTracker(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val processed = ArrayDeque<String>(capacity)

    @Synchronized
    fun contains(transactionId: String): Boolean = processed.contains(transactionId)

    @Synchronized
    fun record(transactionId: String) {
        if (transactionId.isBlank() || processed.contains(transactionId)) return
        processed.addLast(transactionId)
        while (processed.size > capacity) processed.removeFirst()
    }

    @Synchronized
    fun retainedCount(): Int = processed.size

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
