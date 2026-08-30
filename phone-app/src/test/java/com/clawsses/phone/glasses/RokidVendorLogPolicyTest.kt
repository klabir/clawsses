package com.clawsses.phone.glasses

import com.rokid.cxr.client.utils.LogUtil
import org.junit.Assert.assertEquals
import org.junit.Test

class RokidVendorLogPolicyTest {
    @Test
    fun `vendor logging is restricted before SDK initialization`() {
        val events = mutableListOf<String>()

        val initialized = RokidVendorLogPolicy.applyBefore(
            setLogLevel = { level -> events += "level:$level" },
            initializeSdk = {
                events += "initialize"
                true
            },
        )

        assertEquals(true, initialized)
        assertEquals(listOf("level:${LogUtil.WARN}", "initialize"), events)
    }
}
