package com.clawsses.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerInstallVerificationTest {
    private val verification = PeerInstallVerification(expectedBuild = 87)

    @Test
    fun `missing or stale peer remains pending`() {
        assertEquals(PeerVerificationResult.PENDING, verification.observe(null))
        assertEquals(PeerVerificationResult.PENDING, verification.observe(86))
    }

    @Test
    fun `matching peer verifies install`() {
        assertEquals(PeerVerificationResult.VERIFIED, verification.observe(87))
    }
}
