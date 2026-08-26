package com.clawsses.phone.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkRuntimeCoordinatorTest {
    @Test
    fun connectedGlassesRequireBluetoothMediaOutput() {
        assertTrue(shouldRequireGlassesMediaOutput(glassesConnected = true))
    }

    @Test
    fun disconnectedGlassesAllowTheNormalAndroidMediaOutput() {
        assertFalse(shouldRequireGlassesMediaOutput(glassesConnected = false))
    }
}
