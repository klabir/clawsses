package com.clawsses.phone.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionAttemptGateTest {
    @Test
    fun cancelledAttemptCannotDeliverOrStartFallback() {
        val gate = RecognitionAttemptGate()
        val attempt = gate.begin()

        gate.cancel()

        assertFalse(gate.isCurrent(attempt))
        assertFalse(gate.complete(attempt))
    }

    @Test
    fun newerAttemptRejectsCallbacksFromPreviousAttempt() {
        val gate = RecognitionAttemptGate()
        val previous = gate.begin()
        val current = gate.begin()

        assertFalse(gate.isCurrent(previous))
        assertTrue(gate.isCurrent(current))
        assertTrue(gate.complete(current))
        assertFalse(gate.complete(current))
    }
}
