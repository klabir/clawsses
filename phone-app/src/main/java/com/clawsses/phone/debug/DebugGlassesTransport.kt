package com.clawsses.phone.debug

/**
 * Variant-neutral contract for the emulator-only glasses transport.
 *
 * The implementation lives exclusively in the debug source set so the raw,
 * unauthenticated WebSocket listener cannot be packaged in release APKs.
 */
internal interface DebugGlassesTransport {
    var onMessageFromGlasses: ((String) -> Unit)?
    var onGlassesConnected: (() -> Unit)?
    var onGlassesDisconnected: (() -> Unit)?

    fun start()
    fun stop()
    fun sendToGlasses(message: String): Boolean

    companion object {
        const val DEFAULT_PORT = 8081
    }
}
