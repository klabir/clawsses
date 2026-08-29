package com.clawsses.phone.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.clawsses.phone.talk.TalkModeSource

class TalkRuntimeCoordinatorTest {
    @Test
    fun connectedGlassesRequireBluetoothMediaOutput() {
        assertTrue(shouldRequireGlassesMediaOutput(glassesConnected = true))
    }

    @Test
    fun disconnectedGlassesAllowTheNormalAndroidMediaOutput() {
        assertFalse(shouldRequireGlassesMediaOutput(glassesConnected = false))
    }

    @Test
    fun automaticGlassesRestartWaitsForFirmwareAudioTeardown() {
        assertEquals(2_000L, safeTalkRestartDelay(450L, TalkModeSource.GLASSES))
        assertEquals(2_500L, safeTalkRestartDelay(2_500L, TalkModeSource.GLASSES))
        assertEquals(450L, safeTalkRestartDelay(450L, TalkModeSource.PHONE))
    }
}
