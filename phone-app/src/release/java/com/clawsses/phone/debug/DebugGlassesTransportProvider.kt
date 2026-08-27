package com.clawsses.phone.debug

/** Release builds intentionally contain no debug socket transport. */
internal object DebugGlassesTransportProvider {
    fun create(): DebugGlassesTransport? = null
}
