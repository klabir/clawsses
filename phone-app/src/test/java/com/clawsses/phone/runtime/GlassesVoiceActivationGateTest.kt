package com.clawsses.phone.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesVoiceActivationGateTest {
    @Test
    fun `hud command followed by vendor key starts one audio cycle`() {
        val gate = GlassesVoiceActivationGate(cooldownMs = 1_500L)

        assertTrue(gate.tryAccept(10_000L))
        assertFalse(gate.tryAccept(10_003L))
        assertFalse(gate.tryAccept(11_499L))
        assertTrue(gate.tryAccept(11_500L))
    }

    @Test
    fun `vendor key followed by hud command starts one audio cycle`() {
        val gate = GlassesVoiceActivationGate(cooldownMs = 1_500L)

        assertTrue(gate.tryAccept(20_000L))
        assertFalse(gate.tryAccept(20_010L))
    }
}
