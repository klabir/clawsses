package com.clawsses.phone.openclaw

import com.google.gson.JsonObject
import com.clawsses.shared.SessionInfo
import com.clawsses.shared.SessionPageItem
import com.clawsses.shared.SessionPaging

/** Builds the least-privilege parameter set accepted by OpenClaw sessions.create. */
internal object SessionRequestFactory {
    fun listPageParams(offset: Int): JsonObject = JsonObject().apply {
        addProperty("includeDerivedTitles", true)
        addProperty("limit", pageRequestLimit(offset))
        addProperty("offset", offset.coerceAtLeast(0))
    }

    fun pageRequestLimit(offset: Int): Int =
        if (offset <= 0) SessionPaging.PAGE_SIZE - 1 else SessionPaging.PAGE_SIZE

    fun pageItems(
        sessions: List<SessionInfo>,
        offset: Int,
        homeSessionKey: String,
        unreadSessionKeys: Set<String>,
    ): List<SessionPageItem> = buildList {
        if (offset <= 0) {
            add(SessionPageItem(key = homeSessionKey, name = "Home"))
        }
        sessions
            .asSequence()
            .filterNot { it.key == homeSessionKey }
            .map { session ->
                SessionPageItem(
                    key = session.key,
                    name = SessionPaging.compactName(session.name),
                    hasUnread = true.takeIf { session.key in unreadSessionKeys },
                )
            }
            .take(SessionPaging.PAGE_SIZE - size)
            .forEach(::add)
    }

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

    /** Builds the canonical write-scoped OpenClaw model-selection patch. */
    fun modelPatchParams(sessionKey: String, modelRef: String): JsonObject = JsonObject().apply {
        addProperty("key", sessionKey)
        addProperty("model", modelRef)
    }
}
