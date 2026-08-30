package com.clawsses.phone.openclaw

import com.clawsses.phone.media.ChatAttachmentFileStore
import com.clawsses.shared.ChatMessage
import com.clawsses.shared.OpenClawMethods
import com.clawsses.shared.OpenClawResponse
import com.google.gson.JsonObject

/** Owns active-session subscription, authoritative history and bounded pagination work. */
internal class OpenClawActiveSessionRuntime(
    private val catalogSession: OpenClawCatalogSessionComponent,
    private val sessionSync: OpenClawSessionSyncCoordinator,
    private val chatStore: BoundedChatStore,
    private val attachmentFileStore: ChatAttachmentFileStore,
    private val sendRequest: suspend (String, JsonObject?, Long?) -> OpenClawResponse,
    private val onChatHistory: (List<ChatMessage>) -> Unit,
    private val onMoreHistoryLoaded: (Int, Boolean) -> Unit,
    private val logger: ActiveSessionLogger = AndroidActiveSessionLogger,
) {
    data class Operation(val sessionKey: String, val id: Long)

    data class MoreHistoryClaim(
        val operation: Operation,
        val existingMessages: List<ChatMessage>,
        val requestedLimit: Int,
    )

    fun activate(sessionKey: String): Operation {
        val operation = catalogSession.activateSession(sessionKey)
        sessionSync.activate(sessionKey)
        chatStore.clear()
        return Operation(sessionKey, operation)
    }

    fun prepareHistoryLoad(sessionKey: String?): Operation {
        val key = sessionKey ?: catalogSession.currentSessionKey.value ?: "main"
        return if (catalogSession.currentSessionKey.value == key) {
            Operation(key, catalogSession.sessionOperationEpoch.current())
        } else {
            activate(key)
        }
    }

    fun currentOperation(sessionKey: String): Operation = Operation(
        sessionKey = sessionKey,
        id = catalogSession.sessionOperationEpoch.current(),
    )

    suspend fun loadHistory(operation: Operation) {
        loadHistoryNow(operation)
    }

    suspend fun synchronize(
        operation: Operation,
        requiredGeneration: Long? = null,
    ) {
        var previousKey: String? = null
        try {
            val response = sendRequest(
                OpenClawMethods.SESSION_MESSAGES_SUBSCRIBE,
                JsonObject().apply { addProperty("key", operation.sessionKey) },
                requiredGeneration,
            )
            if (response.ok && isCurrent(operation)) {
                previousKey = sessionSync.confirmSubscription(operation.sessionKey)
            } else if (response.ok) {
                runCatching {
                    sendRequest(
                        OpenClawMethods.SESSION_MESSAGES_UNSUBSCRIBE,
                        JsonObject().apply { addProperty("key", operation.sessionKey) },
                        requiredGeneration,
                    )
                }
            } else {
                logger.warn("Session message subscription failed")
            }
        } catch (error: Exception) {
            logger.warn("Could not subscribe to session messages", error)
        }

        if (!isCurrent(operation)) return
        loadHistoryNow(operation)

        previousKey?.let { oldKey ->
            try {
                sendRequest(
                    OpenClawMethods.SESSION_MESSAGES_UNSUBSCRIBE,
                    JsonObject().apply { addProperty("key", oldKey) },
                    requiredGeneration,
                )
            } catch (error: Exception) {
                logger.warn("Could not unsubscribe stale session", error)
            }
        }
    }

    suspend fun refresh(reason: String) {
        val key = catalogSession.currentSessionKey.value ?: return
        val claim = sessionSync.claimHistoryRefresh() ?: return
        val operation = Operation(key, catalogSession.sessionOperationEpoch.current())
        try {
            do {
                logger.debug("Reconciling active session history ($reason)")
                loadHistoryNow(operation)
            } while (sessionSync.completeHistoryRefreshCycle(claim))
        } finally {
            sessionSync.releaseHistoryRefresh(claim)
        }
    }

    fun claimMoreHistory(): MoreHistoryClaim? {
        if (catalogSession.isLoadingMoreHistory.value) return null
        catalogSession.isLoadingMoreHistory.value = true
        val key = catalogSession.currentSessionKey.value ?: "main"
        return MoreHistoryClaim(
            operation = Operation(key, catalogSession.sessionOperationEpoch.current()),
            existingMessages = chatStore.value(),
            requestedLimit = (catalogSession.currentHistoryLimit + PAGE_SIZE)
                .coerceAtMost(MAX_HISTORY_LIMIT),
        )
    }

    suspend fun loadMoreHistory(claim: MoreHistoryClaim) {
        val operation = claim.operation
        try {
            logger.debug(
                "Requesting expanded history (limit=${claim.requestedLimit}, " +
                    "existing=${claim.existingMessages.size})",
            )
            val response = sendRequest(
                OpenClawMethods.CHAT_HISTORY,
                historyParams(operation.sessionKey, claim.requestedLimit),
                null,
            )
            if (!isCurrent(operation)) {
                logger.debug("Discarded stale expanded history response")
                return
            }
            catalogSession.currentHistoryLimit = claim.requestedLimit

            if (response.ok) {
                val parsedHistory = OpenClawChatHistoryParser.parse(
                    operation.sessionKey,
                    response.payload?.getAsJsonArray("messages"),
                )
                val merge = HistoryPrependMerge.merge(
                    attachmentFileStore.materialize(parsedHistory.messages),
                    claim.existingMessages,
                )
                val boundedCombined = chatStore.replace(merge.combined)
                val hasMore = parsedHistory.rawCount >= claim.requestedLimit
                catalogSession.hasMoreHistory.value = hasMore
                catalogSession.isLoadingMoreHistory.value = false
                logger.debug(
                    "Prepended ${merge.prependedCount} older messages " +
                        "(total=${boundedCombined.size}, hasMore=$hasMore)",
                )
                onMoreHistoryLoaded(merge.prependedCount, hasMore)
            } else {
                catalogSession.isLoadingMoreHistory.value = false
                onMoreHistoryLoaded(0, catalogSession.hasMoreHistory.value)
            }
        } catch (error: Exception) {
            if (!isCurrent(operation)) return
            logger.error("Error loading more history", error)
            catalogSession.isLoadingMoreHistory.value = false
            onMoreHistoryLoaded(0, catalogSession.hasMoreHistory.value)
        }
    }

    private suspend fun loadHistoryNow(operation: Operation) {
        try {
            logger.debug("Requesting authoritative chat history")
            val response = sendRequest(
                OpenClawMethods.CHAT_HISTORY,
                historyParams(operation.sessionKey, PAGE_SIZE),
                null,
            )
            if (!isCurrent(operation)) {
                logger.debug("Discarded stale history response")
                return
            }
            if (response.ok) {
                val parsedHistory = OpenClawChatHistoryParser.parse(
                    operation.sessionKey,
                    response.payload?.getAsJsonArray("messages"),
                )
                val boundedHistory = chatStore.replace(
                    attachmentFileStore.materialize(parsedHistory.messages),
                )
                catalogSession.hasMoreHistory.value = parsedHistory.rawCount >= PAGE_SIZE
                logger.debug("Loaded ${boundedHistory.size} authoritative history messages")
                onChatHistory(boundedHistory)
            } else {
                chatStore.clear()
                catalogSession.hasMoreHistory.value = false
                onChatHistory(emptyList())
            }
        } catch (error: Exception) {
            if (!isCurrent(operation)) return
            logger.error("Error loading authoritative history", error)
            chatStore.clear()
            catalogSession.hasMoreHistory.value = false
            onChatHistory(emptyList())
        }
    }

    private fun isCurrent(operation: Operation): Boolean =
        catalogSession.isCurrentOperation(operation.sessionKey, operation.id)

    private fun historyParams(sessionKey: String, limit: Int) = JsonObject().apply {
        addProperty("sessionKey", sessionKey)
        addProperty("limit", limit)
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_HISTORY_LIMIT = 500
    }
}

internal interface ActiveSessionLogger {
    fun debug(message: String)
    fun warn(message: String, error: Throwable? = null)
    fun error(message: String, error: Throwable)
}

private object AndroidActiveSessionLogger : ActiveSessionLogger {
    private const val TAG = "OpenClawActiveSession"

    override fun debug(message: String) {
        android.util.Log.d(TAG, message)
    }

    override fun warn(message: String, error: Throwable?) {
        if (error == null) android.util.Log.w(TAG, message) else android.util.Log.w(TAG, message, error)
    }

    override fun error(message: String, error: Throwable) {
        android.util.Log.e(TAG, message, error)
    }
}
