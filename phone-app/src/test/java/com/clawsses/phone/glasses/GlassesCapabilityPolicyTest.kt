package com.clawsses.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesCapabilityPolicyTest {
    @Test
    fun verifiedFirmwareMatchesHardwareValidatedPair() {
        val snapshot = GlassesCapabilitySnapshot(
            systemVersion = GlassesCapabilityPolicy.VERIFIED_SYSTEM_VERSION,
            assistantVersion = GlassesCapabilityPolicy.VERIFIED_ASSISTANT_VERSION,
            sdkVersionCheckPassed = true,
            glassInfoAvailable = true,
        )

        assertEquals(FirmwareSupport.VERIFIED, GlassesCapabilityPolicy.firmwareSupport(snapshot))
        assertTrue(GlassesCapabilityPolicy.nativeVoiceControlEligible(snapshot))
    }

    @Test
    fun unknownFirmwareRemainsRuntimeProbedAndConservative() {
        val snapshot = GlassesCapabilitySnapshot()

        assertEquals(FirmwareSupport.UNKNOWN, GlassesCapabilityPolicy.firmwareSupport(snapshot))
        assertEquals(
            listOf(InstallerTransport.GLASSES_HOTSPOT, InstallerTransport.VENDOR_P2P),
            GlassesCapabilityPolicy.installTransportOrder(snapshot),
        )
        assertFalse(GlassesCapabilityPolicy.nativeVoiceControlEligible(snapshot))
    }

    @Test
    fun newerUnverifiedFirmwareDoesNotEnableNativeVoiceControls() {
        val snapshot = GlassesCapabilitySnapshot(
            systemVersion = "1.24.013-20260901-010101",
            assistantVersion = "0.3.7",
            sdkVersionCheckPassed = true,
            glassInfoAvailable = true,
        )

        assertEquals(FirmwareSupport.UNVERIFIED, GlassesCapabilityPolicy.firmwareSupport(snapshot))
        assertFalse(GlassesCapabilityPolicy.nativeVoiceControlEligible(snapshot))
    }
}
