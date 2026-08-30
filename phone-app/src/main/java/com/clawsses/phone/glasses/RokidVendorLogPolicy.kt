package com.clawsses.phone.glasses

import com.rokid.cxr.client.utils.LogUtil

/** Prevents CXR-M from logging credential-bearing INFO messages. */
internal object RokidVendorLogPolicy {
    fun <T> applyBefore(
        setLogLevel: (Int) -> Unit = { LogUtil.setLogLevel(it) },
        initializeSdk: () -> T,
    ): T {
        setLogLevel(LogUtil.WARN)
        return initializeSdk()
    }
}
