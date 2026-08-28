package com.clawsses.phone.glasses

/**
 * Idempotent readiness gate for the two independent CXR-L callbacks.
 * Upload may start exactly once after both the service and glasses Bluetooth are ready.
 */
internal data class HiRokidInstallGate(
    val linkConnected: Boolean = false,
    val glassesBluetoothConnected: Boolean = false,
    val uploadStarted: Boolean = false,
) {
    fun withLinkConnected(connected: Boolean) = copy(linkConnected = connected)

    fun withGlassesBluetoothConnected(connected: Boolean) =
        copy(glassesBluetoothConnected = connected)

    fun claimUpload(): HiRokidInstallGate? {
        if (!linkConnected || !glassesBluetoothConnected || uploadStarted) return null
        return copy(uploadStarted = true)
    }
}
