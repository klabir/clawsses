package com.clawsses.phone.glasses

import android.net.NetworkCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotNetworkRequestFactoryTest {
    @Test
    fun `android 14 request excludes internet without local network capability`() {
        val contract = HotspotNetworkRequestFactory.contractFor(34)

        assertEquals(NetworkCapabilities.TRANSPORT_WIFI, contract.transportType)
        assertTrue(contract.removesInternetCapability)
        assertFalse(contract.addsLocalNetworkCapability)
    }

    @Test
    fun `android 15 and newer request local network capability explicitly`() {
        assertTrue(HotspotNetworkRequestFactory.contractFor(35).addsLocalNetworkCapability)
        assertTrue(HotspotNetworkRequestFactory.contractFor(36).addsLocalNetworkCapability)
    }
}
