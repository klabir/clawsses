package com.clawsses.phone.openclaw

import com.clawsses.shared.CxrPayloadLimits
import com.clawsses.shared.SessionListUpdate
import com.clawsses.shared.SessionPageItem
import com.clawsses.shared.SessionPaging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPagingTest {
    @Test
    fun `three compact sessions fit the CXR byte limit`() {
        val sessions = (1..SessionPaging.PAGE_SIZE).map { index ->
            SessionPageItem(
                key = "agent:main:subagent:session-$index-12345678-1234-1234-1234-123456789012",
                name = SessionPaging.compactName("A deliberately long derived session title number $index"),
                hasUnread = true.takeIf { index == 2 },
            )
        }
        val json = SessionListUpdate(
            sessions = sessions,
            offset = 3,
            nextOffset = 6,
            hasMore = true,
        ).toJson()

        assertTrue("payload was ${CxrPayloadLimits.byteSize(json)} bytes", CxrPayloadLimits.fits(json))
    }

    @Test
    fun `payload limits count UTF-8 bytes rather than characters`() {
        val payload = "ü".repeat(251)

        assertEquals(502, CxrPayloadLimits.byteSize(payload))
        assertFalse(CxrPayloadLimits.fits(payload))
    }

    @Test
    fun `session display names are bounded`() {
        val compact = SessionPaging.compactName("x".repeat(80))

        assertEquals(SessionPaging.MAX_DISPLAY_NAME_CHARS, compact.length)
        assertTrue(compact.endsWith("..."))
    }
}
