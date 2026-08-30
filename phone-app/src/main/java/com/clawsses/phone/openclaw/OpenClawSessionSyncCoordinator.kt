package com.clawsses.phone.openclaw

import com.clawsses.shared.ChatMessage
import com.google.gson.JsonObject
import java.util.LinkedHashMap

/** Pure state holder for session-scoped subscriptions and transcript reconciliation. */
internal class OpenClawSessionSyncCoordinator {
    class HistoryRefreshClaim internal constructor(internal val id: Long)

    data class SubscriptionTarget(
        val sessionKey: String,
        val previousSubscribedKey: String?,
    )

    sealed interface MessageDecision {
        data object Ignore : MessageDecision
        data class Accept(
            val replacingLocalId: String?,
            val sequenceGap: Boolean,
        ) : MessageDecision
    }

    private data class OptimisticMessage(val sessionKey: String, val localId: String)

    private var activeSessionKey: String? = null
    private var subscribedSessionKey: String? = null
    private var lastMessageSeq: Long? = null
    private var nextHistoryRefreshClaimId = 0L
    private var activeHistoryRefreshClaimId: Long? = null
    private var historyRefreshPending = false
    private var catalogRefreshClaimed = false
    private val optimisticMessages = LinkedHashMap<String, OptimisticMessage>()

    @Synchronized
    fun resetConnection() {
        subscribedSessionKey = null
        lastMessageSeq = null
        activeHistoryRefreshClaimId = null
        historyRefreshPending = false
        catalogRefreshClaimed = false
    }

    @Synchronized
    fun activate(sessionKey: String): SubscriptionTarget {
        activeSessionKey = sessionKey
        lastMessageSeq = null
        activeHistoryRefreshClaimId = null
        historyRefreshPending = false
        optimisticMessages.entries.removeAll { it.value.sessionKey != sessionKey }
        return SubscriptionTarget(sessionKey, subscribedSessionKey?.takeIf { it != sessionKey })
    }

    @Synchronized
    fun confirmSubscription(sessionKey: String): String? {
        if (sessionKey != activeSessionKey) return null
        val previous = subscribedSessionKey?.takeIf { it != sessionKey }
        subscribedSessionKey = sessionKey
        return previous
    }

    @Synchronized
    fun registerOptimistic(sessionKey: String, idempotencyKey: String, localId: String) {
        optimisticMessages[idempotencyKey] = OptimisticMessage(sessionKey, localId)
        while (optimisticMessages.size > MAX_OPTIMISTIC_MESSAGES) {
            optimisticMessages.remove(optimisticMessages.keys.first())
        }
    }

    @Synchronized
    fun acceptMessage(event: ParsedSessionMessage): MessageDecision {
        if (event.sessionKey != activeSessionKey || event.sessionKey != subscribedSessionKey) {
            return MessageDecision.Ignore
        }
        val previousSeq = lastMessageSeq
        val gap = previousSeq != null && event.messageSeq != null && event.messageSeq > previousSeq + 1
        if (event.messageSeq != null) {
            if (previousSeq != null && event.messageSeq <= previousSeq) return MessageDecision.Ignore
            lastMessageSeq = event.messageSeq
        }
        val replacement = event.idempotencyKey
            ?.let(optimisticMessages::remove)
            ?.takeIf { it.sessionKey == event.sessionKey }
            ?.localId
        return MessageDecision.Accept(replacement, gap)
    }

    @Synchronized
    fun claimHistoryRefresh(): HistoryRefreshClaim? {
        if (activeHistoryRefreshClaimId != null) {
            historyRefreshPending = true
            return null
        }
        val claimId = ++nextHistoryRefreshClaimId
        activeHistoryRefreshClaimId = claimId
        return HistoryRefreshClaim(claimId)
    }

    @Synchronized
    fun completeHistoryRefreshCycle(claim: HistoryRefreshClaim): Boolean {
        if (activeHistoryRefreshClaimId != claim.id) return false
        if (historyRefreshPending) {
            historyRefreshPending = false
            return true
        }
        activeHistoryRefreshClaimId = null
        return false
    }

    @Synchronized
    fun releaseHistoryRefresh(claim: HistoryRefreshClaim) {
        if (activeHistoryRefreshClaimId != claim.id) return
        activeHistoryRefreshClaimId = null
        historyRefreshPending = false
    }

    @Synchronized
    fun claimCatalogRefresh(): Boolean {
        if (catalogRefreshClaimed) return false
        catalogRefreshClaimed = true
        return true
    }

    @Synchronized
    fun completeCatalogRefresh() {
        catalogRefreshClaimed = false
    }

    private companion object {
        const val MAX_OPTIMISTIC_MESSAGES = 64
    }
}

internal data class ParsedSessionMessage(
    val sessionKey: String,
    val message: ChatMessage,
    val idempotencyKey: String?,
    val messageSeq: Long?,
)

internal object SessionMessageEventParser {
    fun parse(payload: JsonObject?): ParsedSessionMessage? {
        payload ?: return null
        val sessionKey = payload.primitiveString("sessionKey")?.takeIf(String::isNotBlank)
            ?: return null
        val rawMessage = payload.get("message")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        val transcriptMetadata = rawMessage.get("__openclaw")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        val message = OpenClawChatHistoryParser.parseMessage(
            sessionKey = sessionKey,
            message = rawMessage,
            explicitId = payload.primitiveString("messageId"),
        ) ?: return null
        return ParsedSessionMessage(
            sessionKey = sessionKey,
            message = message,
            idempotencyKey = transcriptMetadata?.primitiveString("idempotencyKey")
                ?: rawMessage.primitiveString("idempotencyKey")
                ?: payload.primitiveString("idempotencyKey")
                ?: payload.primitiveString("clientRunId"),
            messageSeq = transcriptMetadata?.primitiveLong("seq")
                ?: rawMessage.primitiveLong("seq")
                ?: payload.primitiveLong("messageSeq"),
        )
    }

    private fun JsonObject.primitiveString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.primitiveLong(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asLong }.getOrNull() }
}

internal data class ParsedSessionsChanged(
    val sessionKey: String,
    val phase: String?,
    val reason: String?,
)

internal object SessionsChangedEventParser {
    fun parse(payload: JsonObject?): ParsedSessionsChanged? {
        payload ?: return null
        val sessionKey = payload.get("sessionKey")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.takeIf(String::isNotBlank)
            ?: return null
        fun string(name: String) = payload.get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
        return ParsedSessionsChanged(sessionKey, string("phase"), string("reason"))
    }
}
