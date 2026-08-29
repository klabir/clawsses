package com.clawsses.phone.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CxrConnectionAttemptGateTest {
    @Test
    fun newerAttemptRejectsEveryCallbackFromThePreviousAttempt() {
        val gate = CxrConnectionAttemptGate()
        val previous = gate.begin()
        val current = gate.begin()

        assertFalse(gate.isCurrent(previous))
        assertTrue(gate.isCurrent(current))
    }

    @Test
    fun cancellationInvalidatesTheActiveAttempt() {
        val gate = CxrConnectionAttemptGate()
        val active = gate.begin()

        gate.cancel()

        assertFalse(gate.isCurrent(active))
    }
}
