package com.clawsses.phone.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    @Test
    fun `relay requires exact allowlisted package and a user-visible notification`() {
        val allowed = NotificationFilter.parseAllowedPackages("com.google.android.gm, com.signal\ncom.todo")

        assertTrue(NotificationFilter.shouldRelay("com.signal", allowed, false, false))
        assertFalse(NotificationFilter.shouldRelay("com.signalmaybe", allowed, false, false))
        assertFalse(NotificationFilter.shouldRelay("com.signal", allowed, true, false))
        assertFalse(NotificationFilter.shouldRelay("com.signal", allowed, false, true))
        assertFalse(NotificationFilter.shouldRelay("com.clawsses.phone", setOf("com.clawsses.phone"), false, false))
    }

    @Test
    fun `empty allowlist relays nothing`() {
        assertFalse(NotificationFilter.shouldRelay("com.example", emptySet(), false, false))
    }
}
