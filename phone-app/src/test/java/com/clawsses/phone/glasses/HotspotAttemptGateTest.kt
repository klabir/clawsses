package com.clawsses.phone.glasses

import com.clawsses.phone.glasses.HotspotAttemptGate.AdvertisementDecision.IGNORE_DUPLICATE
import com.clawsses.phone.glasses.HotspotAttemptGate.AdvertisementDecision.IGNORE_STALE
import com.clawsses.phone.glasses.HotspotAttemptGate.AdvertisementDecision.START_CONNECTION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotAttemptGateTest {
    @Test
    fun `duplicate advertisement cannot replace active network request`() {
        val gate = HotspotAttemptGate()
        val attemptId = gate.begin()

        assertEquals(START_CONNECTION, gate.registerAdvertisement(attemptId, "Glasses", "192.168.43.1"))
        assertEquals(IGNORE_DUPLICATE, gate.registerAdvertisement(attemptId, "Glasses", "192.168.43.1"))
        assertTrue(gate.isActive(attemptId))
    }

    @Test
    fun `late callback from prior attempt is ignored`() {
        val gate = HotspotAttemptGate()
        val oldAttempt = gate.begin()
        val currentAttempt = gate.begin()

        assertEquals(IGNORE_STALE, gate.registerAdvertisement(oldAttempt, "Old", "192.168.43.1"))
        assertEquals(START_CONNECTION, gate.registerAdvertisement(currentAttempt, "New", "192.168.43.1"))
    }

    @Test
    fun `only active attempt can end gate`() {
        val gate = HotspotAttemptGate()
        val oldAttempt = gate.begin()
        val currentAttempt = gate.begin()

        assertFalse(gate.end(oldAttempt))
        assertTrue(gate.isActive(currentAttempt))
        assertTrue(gate.end(currentAttempt))
        assertFalse(gate.isActive(currentAttempt))
    }
}
