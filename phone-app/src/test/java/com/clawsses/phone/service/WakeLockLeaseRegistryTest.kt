package com.clawsses.phone.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeLockLeaseRegistryTest {
    @Test
    fun longestActiveLeaseOwnsWakeLockDeadline() {
        val registry = WakeLockLeaseRegistry()

        assertEquals(20_000L, registry.acquire(WakeLockReason.RECONNECT, 0L, 20_000L))
        assertEquals(45_000L, registry.acquire(WakeLockReason.VOICE_RECOGNITION, 0L, 45_000L))
        assertEquals(45_000L, registry.release(WakeLockReason.RECONNECT, 1_000L))
        assertNull(registry.nextExpiration(45_000L))
    }

    @Test
    fun renewingReasonExtendsOnlyThatLease() {
        val registry = WakeLockLeaseRegistry()

        registry.acquire(WakeLockReason.VOICE_RECOGNITION, 1_000L, 10_000L)
        assertEquals(
            25_000L,
            registry.acquire(WakeLockReason.VOICE_RECOGNITION, 5_000L, 20_000L),
        )
        assertNull(registry.nextExpiration(25_000L))
    }
}
