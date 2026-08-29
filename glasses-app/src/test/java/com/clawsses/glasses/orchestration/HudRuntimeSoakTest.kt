package com.clawsses.glasses.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudRuntimeSoakTest {
    @Test
    fun `ten thousand transport transactions retain only the newest ack window`() {
        val tracker = HudTransportTransactionTracker(capacity = 64)

        repeat(10_000) { tracker.record("tx-$it") }
        tracker.record("tx-9999")

        assertEquals(64, tracker.retainedCount())
        assertFalse(tracker.contains("tx-9935"))
        assertTrue(tracker.contains("tx-9936"))
        assertTrue(tracker.contains("tx-9999"))
    }

    @Test
    fun `long runtime counters remain exact and expose no payload fields`() {
        val metrics = HudRuntimeMetrics()

        repeat(10_000) {
            metrics.recordPhoneMessage()
            metrics.recordGesture()
            if (it % 10 == 0) metrics.recordStreamPublication()
            if (it % 100 == 0) metrics.recordReconnectStateRequest()
            if (it % 250 == 0) metrics.recordDuplicateTransaction()
            if (it % 500 == 0) metrics.recordMalformedMessage()
        }
        repeat(2_500) { metrics.recordCommand() }

        val snapshot = metrics.snapshot()
        assertEquals(10_000, snapshot.phoneMessages)
        assertEquals(10_000, snapshot.gestures)
        assertEquals(2_500, snapshot.commands)
        assertEquals(1_000, snapshot.streamPublications)
        assertEquals(100, snapshot.reconnectStateRequests)
        assertEquals(40, snapshot.duplicateTransactions)
        assertEquals(20, snapshot.malformedMessages)
        assertFalse(snapshot.toLogLine().contains("content", ignoreCase = true))
        assertFalse(snapshot.toLogLine().contains("transactionId", ignoreCase = true))
    }
}
