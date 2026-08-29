package com.clawsses.phone.glasses

internal enum class PeerVerificationResult {
    PENDING,
    VERIFIED,
}

/** Pure peer-build gate used across installer ownership handoffs and process restarts. */
internal data class PeerInstallVerification(val expectedBuild: Int) {
    init {
        require(expectedBuild > 0)
    }

    fun observe(peerBuild: Int?): PeerVerificationResult =
        if (peerBuild == expectedBuild) PeerVerificationResult.VERIFIED
        else PeerVerificationResult.PENDING
}
