package com.clawsses.shared

/** Backwards-compatible feature negotiation for the phone/HUD JSON protocol. */
object PeerProtocol {
    const val CURRENT_VERSION = 1

    const val TRANSPORT_ACK = "transport_ack"
    const val CHUNKED_HISTORY = "chunked_history"
    const val MODEL_PAGING = "model_paging"
    const val PAGE_NAVIGATION = "page_navigation"

    val HUD_CAPABILITIES: List<String> = listOf(
        TRANSPORT_ACK,
        CHUNKED_HISTORY,
        MODEL_PAGING,
        PAGE_NAVIGATION,
    )

    val PHONE_CAPABILITIES: List<String> = listOf(
        CHUNKED_HISTORY,
        MODEL_PAGING,
    )

    fun normalizeCapabilities(values: Iterable<String>?): Set<String> = (values ?: emptyList())
        .asSequence()
        .map(String::trim)
        .filter { it.length in 1..48 && it.all { char -> char.isLowerCase() || char == '_' || char.isDigit() } }
        .take(MAX_CAPABILITIES)
        .toSet()

    fun supports(
        capability: String,
        protocolVersion: Int?,
        capabilities: Set<String>,
        legacyBuildSupports: Boolean,
    ): Boolean = if (protocolVersion == null) legacyBuildSupports else capability in capabilities

    fun compatibility(protocolVersion: Int?): PeerCompatibility = when {
        protocolVersion == null -> PeerCompatibility.LEGACY
        protocolVersion <= 0 -> PeerCompatibility.INVALID
        protocolVersion > CURRENT_VERSION -> PeerCompatibility.NEWER
        else -> PeerCompatibility.COMPATIBLE
    }

    private const val MAX_CAPABILITIES = 16
}

enum class PeerCompatibility {
    LEGACY,
    COMPATIBLE,
    NEWER,
    INVALID,
}

data class PeerDescriptor(
    val versionName: String? = null,
    val versionCode: Int? = null,
    val protocolVersion: Int? = null,
    val capabilities: Set<String> = emptySet(),
) {
    val compatibility: PeerCompatibility
        get() = PeerProtocol.compatibility(protocolVersion)
}
