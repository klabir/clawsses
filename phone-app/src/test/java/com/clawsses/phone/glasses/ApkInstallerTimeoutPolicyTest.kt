package com.clawsses.phone.glasses

import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallerTimeoutPolicyTest {
    @Test
    fun `outer timeout covers every bounded installer phase`() {
        assertTrue(
            ApkInstallerTimeoutPolicy.worstCasePhaseBudgetMs() <=
                ApkInstallerTimeoutPolicy.TOTAL_OPERATION_MS,
        )
    }
}
