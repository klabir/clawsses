package com.clawsses.phone.openclaw

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

interface NetworkMonitor {
    fun isNetworkAvailable(): Boolean
    fun start(onAvailabilityChanged: (Boolean) -> Unit)
    fun stop()
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun start(onAvailabilityChanged: (Boolean) -> Unit) {
        if (callback != null) return
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onAvailabilityChanged(isNetworkAvailable())

            override fun onLost(network: Network) = onAvailabilityChanged(isNetworkAvailable())

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                onAvailabilityChanged(isNetworkAvailable())
            }
        }.also(connectivityManager::registerDefaultNetworkCallback)
        onAvailabilityChanged(isNetworkAvailable())
    }

    override fun stop() {
        callback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        callback = null
    }
}
