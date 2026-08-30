package com.clawsses.phone.glasses

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi

internal data class HotspotNetworkRequestContract(
    val transportType: Int,
    val removesInternetCapability: Boolean,
    val addsLocalNetworkCapability: Boolean,
)

internal object HotspotNetworkRequestFactory {
    fun contractFor(sdkInt: Int): HotspotNetworkRequestContract =
        HotspotNetworkRequestContract(
            transportType = NetworkCapabilities.TRANSPORT_WIFI,
            removesInternetCapability = true,
            addsLocalNetworkCapability = sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM,
        )

    @RequiresApi(Build.VERSION_CODES.Q)
    fun create(
        ssid: String,
        password: String,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): NetworkRequest {
        val contract = contractFor(sdkInt)
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        return NetworkRequest.Builder()
            .addTransportType(contract.transportType)
            .apply {
                if (contract.removesInternetCapability) {
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
                if (contract.addsLocalNetworkCapability) {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
                }
            }
            .setNetworkSpecifier(specifier)
            .build()
    }
}
