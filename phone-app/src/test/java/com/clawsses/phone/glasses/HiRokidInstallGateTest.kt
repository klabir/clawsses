package com.clawsses.phone.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiRokidInstallGateTest {
    @Test
    fun `upload waits for both callbacks regardless of order`() {
        val linkFirst = HiRokidInstallGate().withLinkConnected(true)
        assertNull(linkFirst.claimUpload())
        assertNotNull(linkFirst.withGlassesBluetoothConnected(true).claimUpload())

        val bluetoothFirst = HiRokidInstallGate().withGlassesBluetoothConnected(true)
        assertNull(bluetoothFirst.claimUpload())
        assertNotNull(bluetoothFirst.withLinkConnected(true).claimUpload())
    }

    @Test
    fun `upload can only be claimed once`() {
        val ready = HiRokidInstallGate(
            linkConnected = true,
            glassesBluetoothConnected = true,
        )
        val claimed = requireNotNull(ready.claimUpload())
        assertTrue(claimed.uploadStarted)
        assertNull(claimed.claimUpload())
    }

    @Test
    fun `disconnect callback closes readiness`() {
        val ready = HiRokidInstallGate(
            linkConnected = true,
            glassesBluetoothConnected = true,
        )
        val disconnected = ready.withGlassesBluetoothConnected(false)
        assertFalse(disconnected.glassesBluetoothConnected)
        assertNull(disconnected.claimUpload())
    }
}
