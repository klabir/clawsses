package com.clawsses.phone.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiActivationGateTest {
    @Test
    fun duplicateFirmwareCallbacksCollapseIntoOneActivation() {
        val gate = AiActivationGate(cooldownMs = 750L)

        assertTrue(gate.tryAccept(1_000L))
        assertFalse(gate.tryAccept(1_048L))
        assertFalse(gate.tryAccept(1_749L))
        assertTrue(gate.tryAccept(1_750L))
    }
}
