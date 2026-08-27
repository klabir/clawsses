package com.clawsses.phone.glasses

internal object ApkInstallerTimeoutPolicy {
    const val TOTAL_OPERATION_MS = 240_000L
    const val CAPABILITY_WAIT_MS = 3_000L
    const val HOTSPOT_RESET_DELAY_MS = 1_500L
    const val HOTSPOT_CONNECTION_MS = 30_000L
    const val P2P_ATTEMPTS = 2
    const val P2P_ATTEMPT_MS = 30_000L
    const val P2P_RETRY_DELAY_MS = 1_500L
    const val INSTALL_COMPLETION_MS = 120_000L

    fun worstCasePhaseBudgetMs(): Long =
        CAPABILITY_WAIT_MS +
            HOTSPOT_RESET_DELAY_MS +
            HOTSPOT_CONNECTION_MS +
            (P2P_ATTEMPTS * P2P_ATTEMPT_MS) +
            ((P2P_ATTEMPTS - 1) * P2P_RETRY_DELAY_MS) +
            INSTALL_COMPLETION_MS
}
