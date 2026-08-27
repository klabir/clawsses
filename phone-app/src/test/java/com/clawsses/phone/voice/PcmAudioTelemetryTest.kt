package com.clawsses.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmAudioTelemetryTest {
    @Test
    fun `counts bytes and retains maximum absolute PCM16 peak`() {
        val telemetry = PcmAudioTelemetry()

        telemetry.recordPcm16(byteArrayOf(0, 0, 0xE8.toByte(), 0x03)) // 0, 1000
        telemetry.recordPcm16(byteArrayOf(0x30, 0xF8.toByte())) // -2000

        assertEquals(PcmAudioTelemetrySnapshot(totalBytes = 6, maxPeak = 2000), telemetry.snapshot())
    }

    @Test
    fun `handles minimum PCM16 value without overflow`() {
        val telemetry = PcmAudioTelemetry()

        telemetry.recordPcm16(byteArrayOf(0, 0x80.toByte()))

        assertEquals(32767, telemetry.snapshot().maxPeak)
    }

    @Test
    fun `reset clears accumulated diagnostics`() {
        val telemetry = PcmAudioTelemetry()
        telemetry.recordPcm16(byteArrayOf(1, 0))

        telemetry.reset()

        assertEquals(PcmAudioTelemetrySnapshot(totalBytes = 0, maxPeak = 0), telemetry.snapshot())
    }
}
