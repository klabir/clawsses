package com.clawsses.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallerTransportPolicyTest {
    @Test
    fun `official Hi Rokid route is preferred when it can request authorization`() {
        assertEquals(
            InstallerRoute.HI_ROKID,
            InstallerTransportPolicy.select(
                InstallerAvailability(
                    hiRokidAvailable = true,
                    authorizationLauncherAvailable = true,
                    cxrReady = true,
                    cxrConnected = true,
                ),
            ),
        )
    }

    @Test
    fun `connected CXR-M is the automatic fallback`() {
        assertEquals(
            InstallerRoute.CXR_M,
            InstallerTransportPolicy.select(
                InstallerAvailability(
                    hiRokidAvailable = false,
                    authorizationLauncherAvailable = true,
                    cxrReady = true,
                    cxrConnected = true,
                ),
            ),
        )
    }

    @Test
    fun `no route is selected without a usable authorization or CXR connection`() {
        assertNull(
            InstallerTransportPolicy.select(
                InstallerAvailability(
                    hiRokidAvailable = true,
                    authorizationLauncherAvailable = false,
                    cxrReady = true,
                    cxrConnected = false,
                ),
            ),
        )
    }
}
