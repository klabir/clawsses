package com.clawsses.phone.openclaw

import com.clawsses.shared.OpenClawMethods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRequestFactoryTest {
    @Test
    fun `session create uses the current plural gateway method`() {
        assertEquals("sessions.create", OpenClawMethods.SESSION_CREATE)
    }

    @Test
    fun `create params use only the active agent id`() {
        val params = SessionRequestFactory.createParams("agent:work:main")

        assertEquals(setOf("agentId"), params.keySet())
        assertEquals("work", params.get("agentId").asString)
        assertFalse(params.has("key"))
        assertFalse(params.has("parentSessionKey"))
        assertFalse(params.has("cwd"))
        assertFalse(params.has("incognito"))
    }

    @Test
    fun `create params fall back to main for an unscoped session`() {
        val params = SessionRequestFactory.createParams("main")

        assertEquals("main", params.get("agentId").asString)
        assertNull(SessionRequestFactory.agentIdFromSessionKey("main"))
    }
}
