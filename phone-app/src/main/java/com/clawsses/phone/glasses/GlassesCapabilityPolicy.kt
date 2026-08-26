package com.clawsses.phone.glasses

enum class FirmwareSupport {
    VERIFIED,
    UNVERIFIED,
    UNKNOWN,
}

enum class InstallerTransport {
    GLASSES_HOTSPOT,
    VENDOR_P2P,
}

data class GlassesCapabilitySnapshot(
    val systemVersion: String? = null,
    val assistantVersion: String? = null,
    val sdkVersionCheckPassed: Boolean? = null,
    val glassInfoAvailable: Boolean = false,
    val hotspotAdvertised: Boolean = false,
    val p2pPeerAdvertised: Boolean = false,
)

/** Conservative policy: trust only hardware-verified firmware and probe transports at runtime. */
object GlassesCapabilityPolicy {
    const val VERIFIED_SYSTEM_VERSION = "1.24.012-20260825-150201"
    const val VERIFIED_ASSISTANT_VERSION = "0.3.6"

    fun firmwareSupport(snapshot: GlassesCapabilitySnapshot): FirmwareSupport = when {
        !snapshot.glassInfoAvailable || snapshot.systemVersion.isNullOrBlank() -> FirmwareSupport.UNKNOWN
        snapshot.systemVersion == VERIFIED_SYSTEM_VERSION &&
            snapshot.assistantVersion == VERIFIED_ASSISTANT_VERSION -> FirmwareSupport.VERIFIED
        else -> FirmwareSupport.UNVERIFIED
    }

    fun installTransportOrder(snapshot: GlassesCapabilitySnapshot): List<InstallerTransport> {
        // Current firmware is hardware-verified with hotspot-first deployment. Unknown/new
        // firmware uses the same conservative runtime probe; vendor P2P remains a legacy fallback.
        return listOf(InstallerTransport.GLASSES_HOTSPOT, InstallerTransport.VENDOR_P2P)
    }

    fun nativeVoiceControlEligible(snapshot: GlassesCapabilitySnapshot): Boolean =
        firmwareSupport(snapshot) == FirmwareSupport.VERIFIED &&
            snapshot.sdkVersionCheckPassed == true
}
