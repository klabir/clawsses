package com.clawsses.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotNetworkLeaseTest {
    @Test
    fun `release unbinds and unregisters exactly once`() {
        val binds = mutableListOf<String?>()
        val unregistered = mutableListOf<String>()
        val lease = HotspotNetworkLease<String, String>(
            bindProcess = { network -> binds += network; true },
            unregister = unregistered::add,
        )

        lease.replace("callback")
        assertTrue(lease.bind("callback", "network"))
        assertTrue(lease.release())
        assertFalse(lease.release())

        assertEquals(listOf("network", null), binds)
        assertEquals(listOf("callback"), unregistered)
    }

    @Test
    fun `replacement cleans old request and rejects its late callback`() {
        val binds = mutableListOf<String?>()
        val unregistered = mutableListOf<String>()
        val lease = HotspotNetworkLease<String, String>(
            bindProcess = { network -> binds += network; true },
            unregister = unregistered::add,
        )

        val oldCallback = String(charArrayOf('o', 'l', 'd'))
        val newCallback = String(charArrayOf('n', 'e', 'w'))
        lease.replace(oldCallback)
        lease.replace(newCallback)

        assertFalse(lease.bind(oldCallback, "stale-network"))
        assertTrue(lease.bind(newCallback, "current-network"))

        assertEquals(listOf<String?>(null, "current-network"), binds)
        assertEquals(listOf(oldCallback), unregistered)
    }

    @Test
    fun `stale callback cannot release current request`() {
        val unregistered = mutableListOf<String>()
        val lease = HotspotNetworkLease<String, String>(
            bindProcess = { true },
            unregister = unregistered::add,
        )
        val oldCallback = String(charArrayOf('o', 'l', 'd'))
        val currentCallback = String(charArrayOf('n', 'e', 'w'))
        lease.replace(currentCallback)

        assertFalse(lease.release(oldCallback))
        assertTrue(lease.release(currentCallback))
        assertEquals(listOf(currentCallback), unregistered)
    }

    @Test
    fun `unbind failure cannot skip callback cleanup`() {
        val unregistered = mutableListOf<String>()
        val lease = HotspotNetworkLease<String, String>(
            bindProcess = { throw IllegalStateException("network service unavailable") },
            unregister = unregistered::add,
        )
        lease.replace("callback")

        assertTrue(lease.release())
        assertEquals(listOf("callback"), unregistered)
    }
}
