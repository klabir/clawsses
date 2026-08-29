package com.clawsses.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerProtocolTest {
    @Test
    fun `explicit capability overrides legacy build inference`() {
        assertFalse(PeerProtocol.supports(PeerProtocol.TRANSPORT_ACK, 1, emptySet(), true))
        assertTrue(
            PeerProtocol.supports(
                PeerProtocol.TRANSPORT_ACK,
                1,
                setOf(PeerProtocol.TRANSPORT_ACK),
                false,
            ),
        )
    }

    @Test
    fun `missing protocol keeps legacy compatibility`() {
        assertTrue(PeerProtocol.supports(PeerProtocol.TRANSPORT_ACK, null, emptySet(), true))
        assertEquals(PeerCompatibility.LEGACY, PeerProtocol.compatibility(null))
    }

    @Test
    fun `capabilities are bounded and normalized`() {
        val normalized = PeerProtocol.normalizeCapabilities(
            listOf(" transport_ack ", "INVALID-CAP", "", "model_paging"),
        )

        assertEquals(setOf("transport_ack", "model_paging"), normalized)
    }
}
