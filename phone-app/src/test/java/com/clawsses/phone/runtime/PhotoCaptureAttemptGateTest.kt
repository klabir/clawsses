package com.clawsses.phone.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCaptureAttemptGateTest {
    @Test
    fun rejectsOverlapAndIgnoresStaleCompletion() {
        val gate = PhotoCaptureAttemptGate()
        val first = gate.begin() as PhotoCaptureAttemptGate.BeginResult.Started

        assertTrue(gate.begin() is PhotoCaptureAttemptGate.BeginResult.Busy)
        assertFalse(gate.complete(first.attemptId + 1))
        assertTrue(gate.complete(first.attemptId))
        assertTrue(gate.begin() is PhotoCaptureAttemptGate.BeginResult.Started)
    }
}
