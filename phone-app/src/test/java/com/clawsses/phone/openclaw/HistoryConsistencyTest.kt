package com.clawsses.phone.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryConsistencyTest {
    @Test
    fun `fallback history id is stable when older messages are prepended`() {
        val initial = stableHistoryMessageId(
            sessionKey = "agent:main:main",
            explicitId = null,
            role = "assistant",
            content = "same response",
            timestamp = 1234L,
            tailIndex = 2,
        )
        val expanded = stableHistoryMessageId(
            sessionKey = "agent:main:main",
            explicitId = null,
            role = "assistant",
            content = "same response",
            timestamp = 1234L,
            tailIndex = 2,
        )

        assertEquals(initial, expanded)
    }

    @Test
    fun `gateway history id is retained unchanged`() {
        assertEquals(
            "gateway-message",
            stableHistoryMessageId("session", "gateway-message", "user", "hello", 1L, 0),
        )
    }

    @Test
    fun `new session operation invalidates older asynchronous response`() {
        val epoch = SessionOperationEpoch()
        val first = epoch.begin()
        val second = epoch.begin()

        assertFalse(epoch.isCurrent(first))
        assertTrue(epoch.isCurrent(second))
    }
}
