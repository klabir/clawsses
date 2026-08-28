package com.clawsses.glasses.debug

interface DebugPhoneTransport {
    var onMessageFromPhone: ((String) -> Unit)?
    var onConnected: (() -> Unit)?
    var onDisconnected: (() -> Unit)?

    fun connect(host: String, port: Int)
    fun sendToPhone(message: String): Boolean
    fun disconnect()
}

object DebugPhoneTransportDefaults {
    const val HOST = "10.0.2.2"
    const val PORT = 8081
}
