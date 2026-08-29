package com.clawsses.phone.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugGlassesServerTest {
    @Test
    fun `debug listener address is loopback only`() {
        val address = DebugGlassesServer.loopbackAddress(8081)

        assertTrue(address.address.isLoopbackAddress)
        assertEquals(8081, address.port)
    }

    @Test
    fun `debug limits remain finite`() {
        assertTrue(DebugGlassesServer.MAX_RUNTIME_MS in 1..(60L * 60L * 1_000L))
        assertTrue(DebugGlassesServer.MAX_HANDSHAKE_BYTES in 1..(64 * 1_024))
        assertTrue(DebugGlassesServer.MAX_FRAME_BYTES in 1..(1024 * 1_024))
    }
}
