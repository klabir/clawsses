package com.clawsses.phone.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun `delay grows exponentially and remains bounded`() {
        val backoff = ReconnectBackoff(
            baseDelayMs = 3_000,
            maxDelayMs = 30_000,
            randomUnit = { 0.5 },
        )

        assertEquals(
            listOf(3_000L, 6_000L, 12_000L, 24_000L, 30_000L, 30_000L),
            List(6) { backoff.nextDelayMs() },
        )
    }

    @Test
    fun `reset starts a fresh reconnect sequence`() {
        val backoff = ReconnectBackoff(
            baseDelayMs = 3_000,
            maxDelayMs = 30_000,
            randomUnit = { 0.5 },
        )
        backoff.nextDelayMs()
        backoff.nextDelayMs()

        backoff.reset()

        assertEquals(3_000L, backoff.nextDelayMs())
    }

    @Test
    fun `jitter never exceeds configured bounds`() {
        val low = ReconnectBackoff(3_000, 30_000, randomUnit = { -1.0 })
        val high = ReconnectBackoff(30_000, 30_000, randomUnit = { 2.0 })

        assertEquals(2_400L, low.nextDelayMs())
        assertEquals(30_000L, high.nextDelayMs())
        assertTrue(high.nextDelayMs() <= 30_000L)
    }
}

class ConnectionEpochTest {
    @Test
    fun `new connection invalidates every previous callback generation`() {
        val epoch = ConnectionEpoch()
        val first = epoch.begin()
        val second = epoch.begin()

        assertTrue(!epoch.isCurrent(first))
        assertTrue(epoch.isCurrent(second))
    }

    @Test
    fun `connection termination is accepted only once`() {
        val epoch = ConnectionEpoch()
        val generation = epoch.begin()

        assertTrue(epoch.markEnded(generation))
        assertTrue(epoch.isEnded(generation))
        assertTrue(!epoch.markEnded(generation))
    }

    @Test
    fun `explicit invalidation rejects late terminal callback`() {
        val epoch = ConnectionEpoch()
        val generation = epoch.begin()

        epoch.invalidate()

        assertTrue(!epoch.isCurrent(generation))
        assertTrue(!epoch.markEnded(generation))
    }
}
