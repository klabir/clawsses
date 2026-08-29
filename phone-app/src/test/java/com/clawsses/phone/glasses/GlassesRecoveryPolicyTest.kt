package com.clawsses.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesRecoveryPolicyTest {
    @Test
    fun `reconnect budget classifies deep sleep and then stops`() {
        val tracker = GlassesRecoveryTracker(
            deepSleepAttemptThreshold = 3,
            maxAutomaticReconnectAttempts = 5,
        )

        assertTrue(tracker.onReconnectAttempt(1))
        assertTrue(tracker.onReconnectAttempt(2))
        assertTrue(tracker.onReconnectAttempt(3))
        assertTrue(tracker.current().needsPhysicalWake)
        assertTrue(tracker.onReconnectAttempt(5))
        assertFalse(tracker.onReconnectAttempt(6))
        assertEquals(1, tracker.current().deepSleepDetections)
    }

    @Test
    fun `successful connection records one recovery and clears transient failures`() {
        val tracker = GlassesRecoveryTracker()
        tracker.onReconnectAttempt(3)
        tracker.onReconnectTimeout()

        val recovered = tracker.onConnected()

        assertEquals(GlassesRecoveryStatus.RESPONSIVE, recovered.status)
        assertEquals(0, recovered.reconnectAttempt)
        assertEquals(1, recovered.successfulRecoveries)
        assertEquals(1, recovered.reconnectTimeouts)
    }

    @Test
    fun `wake timeout marks a connected but silent device as deep sleep`() {
        val tracker = GlassesRecoveryTracker()
        tracker.onConnected()

        val timedOut = tracker.onWakeTimeout()

        assertTrue(timedOut.needsPhysicalWake)
        assertEquals(1, timedOut.consecutiveWakeTimeouts)
        assertEquals(1, timedOut.deepSleepDetections)
    }

    @Test
    fun `responsive activity clears deep sleep and records wake recovery`() {
        val tracker = GlassesRecoveryTracker()
        tracker.onWakeTimeout()

        val responsive = tracker.onResponsiveActivity()

        assertEquals(GlassesRecoveryStatus.RESPONSIVE, responsive.status)
        assertEquals(0, responsive.consecutiveWakeTimeouts)
        assertEquals(1, responsive.successfulRecoveries)
    }
}
