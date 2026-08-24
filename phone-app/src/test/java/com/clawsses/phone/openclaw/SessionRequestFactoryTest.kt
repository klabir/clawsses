package com.clawsses.phone.openclaw

import com.clawsses.shared.OpenClawMethods
import com.clawsses.shared.SessionInfo
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
    fun `glasses session pages request a bounded offset page`() {
        val params = SessionRequestFactory.listPageParams(6)

        assertEquals(3, params.get("limit").asInt)
        assertEquals(6, params.get("offset").asInt)
        assertEquals(true, params.get("includeDerivedTitles").asBoolean)
    }

    @Test
    fun `first glasses page reserves one row for Home`() {
        val params = SessionRequestFactory.listPageParams(0)

        assertEquals(2, params.get("limit").asInt)
        assertEquals(0, params.get("offset").asInt)
    }

    @Test
    fun `Home is pinned and uses the canonical label`() {
        val homeKey = "agent:main:main"
        val items = SessionRequestFactory.pageItems(
            sessions = listOf(
                SessionInfo("agent:main:dashboard:one", derivedTitle = "Recent chat"),
                SessionInfo("agent:main:dashboard:two", label = "Glasses"),
            ),
            offset = 0,
            homeSessionKey = homeKey,
            unreadSessionKeys = emptySet(),
        )

        assertEquals(listOf(homeKey, "agent:main:dashboard:one", "agent:main:dashboard:two"), items.map { it.key })
        assertEquals("Home", items.first().name)
    }

    @Test
    fun `later pages remove the server copy of Home without skipping other rows`() {
        val items = SessionRequestFactory.pageItems(
            sessions = listOf(
                SessionInfo("agent:main:main", derivedTitle = "Misleading derived title"),
                SessionInfo("agent:main:dashboard:one", derivedTitle = "One"),
                SessionInfo("agent:main:dashboard:two", derivedTitle = "Two"),
            ),
            offset = 2,
            homeSessionKey = "agent:main:main",
            unreadSessionKeys = setOf("agent:main:dashboard:two"),
        )

        assertEquals(listOf("agent:main:dashboard:one", "agent:main:dashboard:two"), items.map { it.key })
        assertEquals(true, items.last().hasUnread)
    }

    @Test
    fun `negative session page offsets are clamped`() {
        val params = SessionRequestFactory.listPageParams(-5)

        assertEquals(0, params.get("offset").asInt)
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
