package com.clawsses.phone.glasses

/**
 * Owns the process binding and callback registration for one hotspot request.
 * A callback from a replaced request can never bind the process again.
 */
internal class HotspotNetworkLease<C : Any, N : Any>(
    private val bindProcess: (N?) -> Boolean,
    private val unregister: (C) -> Unit,
) {
    private var activeCallback: C? = null

    @Synchronized
    fun replace(callback: C) {
        releaseLocked()
        activeCallback = callback
    }

    @Synchronized
    fun bind(callback: C, network: N): Boolean {
        if (activeCallback !== callback) return false
        return runCatching { bindProcess(network) }.getOrDefault(false)
    }

    @Synchronized
    fun release(callback: C? = null): Boolean {
        if (callback != null && activeCallback !== callback) return false
        return releaseLocked()
    }

    private fun releaseLocked(): Boolean {
        val callback = activeCallback ?: return false
        activeCallback = null
        runCatching { bindProcess(null) }
        runCatching { unregister(callback) }
        return true
    }
}
