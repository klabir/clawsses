package com.clawsses.phone.openclaw

import com.google.gson.JsonObject

/** Builds the least-privilege parameter set accepted by OpenClaw sessions.create. */
internal object SessionRequestFactory {
    fun agentIdFromSessionKey(sessionKey: String?): String? {
        if (sessionKey.isNullOrBlank()) return null
        val parts = sessionKey.split(':')
        return parts.getOrNull(1)?.takeIf {
            parts.firstOrNull() == "agent" && it.isNotBlank()
        }
    }

    fun createParams(sessionKey: String?): JsonObject = JsonObject().apply {
        addProperty("agentId", agentIdFromSessionKey(sessionKey) ?: "main")
    }
}
