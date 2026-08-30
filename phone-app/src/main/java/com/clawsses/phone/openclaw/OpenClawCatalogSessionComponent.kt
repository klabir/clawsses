package com.clawsses.phone.openclaw

import com.clawsses.shared.AgentInfo
import com.clawsses.shared.AgentListUpdate
import com.clawsses.shared.ModelInfo
import com.clawsses.shared.SessionInfo
import com.clawsses.shared.SessionListUpdate
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Owns session, agent and model catalog state behind the client facade. */
internal class OpenClawCatalogSessionComponent {
    val currentSessionKey = MutableStateFlow<String?>(null)
    val isLoadingMoreHistory = MutableStateFlow(false)
    val hasMoreHistory = MutableStateFlow(true)
    val unreadSessions = MutableStateFlow<Set<String>>(emptySet())
    val sessionList = MutableStateFlow<List<SessionInfo>>(emptyList())
    val agentList = MutableStateFlow<List<AgentInfo>>(emptyList())
    val modelList = MutableStateFlow<List<ModelInfo>>(emptyList())
    val currentModelRef = MutableStateFlow<String?>(null)
    val isSelectingModel = MutableStateFlow(false)
    val modelSelectionError = MutableStateFlow<String?>(null)
    val sessionOperationEpoch = SessionOperationEpoch()

    var homeSessionKey = "agent:main:main"
    var currentHistoryLimit = 50

    fun activateSession(sessionKey: String): Long {
        val operation = sessionOperationEpoch.begin()
        currentSessionKey.value = sessionKey
        currentHistoryLimit = 50
        hasMoreHistory.value = true
        isLoadingMoreHistory.value = false
        unreadSessions.value = unreadSessions.value - sessionKey
        return operation
    }

    fun isCurrentOperation(sessionKey: String, operation: Long): Boolean =
        currentSessionKey.value == sessionKey && sessionOperationEpoch.isCurrent(operation)

    fun parseSessions(payload: JsonObject?): List<SessionInfo> =
        payload?.getAsJsonArray("sessions")?.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val key = obj.get("key")?.asString?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SessionInfo(
                key = key,
                displayName = obj.get("displayName")?.takeIf { !it.isJsonNull }?.asString,
                label = obj.get("label")?.takeIf { !it.isJsonNull }?.asString,
                derivedTitle = obj.get("derivedTitle")?.takeIf { !it.isJsonNull }?.asString,
                updatedAt = obj.get("updatedAt")?.takeIf { !it.isJsonNull }?.asLong,
                kind = obj.get("kind")?.takeIf { !it.isJsonNull }?.asString,
            )
        }.orEmpty()

    fun sessionPage(payload: JsonObject?, offset: Int): SessionListUpdate {
        val sessions = parseSessions(payload)
        val requestLimit = SessionRequestFactory.pageRequestLimit(offset)
        val hasMore = payload?.get("hasMore")?.takeIf { it.isJsonPrimitive }?.asBoolean
            ?: (sessions.size == requestLimit)
        val nextOffset = payload?.get("nextOffset")
            ?.takeIf { it.isJsonPrimitive }
            ?.asInt
            ?: if (hasMore) offset + sessions.size else null
        return SessionListUpdate(
            sessions = SessionRequestFactory.pageItems(
                sessions = sessions,
                offset = offset,
                homeSessionKey = homeSessionKey,
                unreadSessionKeys = unreadSessions.value,
            ),
            offset = offset,
            nextOffset = nextOffset,
            hasMore = hasMore,
        )
    }

    fun applyAgentCatalog(payload: JsonObject?): String? {
        val parsed = parseAgentCatalog(payload)
        agentList.value = parsed.agents
        return parsed.defaultAgentId
    }

    fun applyModelCatalog(models: List<ModelInfo>, sessionModel: String?): ParsedModelCatalog {
        val sessionKey = currentSessionKey.value
        val agentModel = agentList.value
            .firstOrNull { it.id == SessionRequestFactory.agentIdFromSessionKey(sessionKey) }
            ?.model
            ?.takeIf { candidate -> models.any { it.ref == candidate } }
        return ParsedModelCatalog(
            models = models,
            currentModel = sessionModel ?: agentModel ?: currentModelRef.value,
        ).also { catalog ->
            modelList.value = catalog.models
            currentModelRef.value = catalog.currentModel
            modelSelectionError.value = null
        }
    }

    fun currentAgentListUpdate(defaultAgentId: String? = null): AgentListUpdate =
        buildAgentListUpdate(
            agents = agentList.value,
            currentAgentId = SessionRequestFactory.agentIdFromSessionKey(currentSessionKey.value)
                ?: defaultAgentId,
            currentModelRef = currentModelRef.value,
        )
}

internal class SessionOperationEpoch {
    private val epoch = AtomicLong()

    fun begin(): Long = epoch.incrementAndGet()
    fun current(): Long = epoch.get()
    fun isCurrent(operation: Long): Boolean = epoch.get() == operation
}

internal data class ParsedModelCatalog(
    val models: List<ModelInfo>,
    val currentModel: String?,
)

internal data class ParsedAgentCatalog(
    val agents: List<AgentInfo>,
    val defaultAgentId: String?,
)

internal fun parseAgentCatalog(payload: JsonObject?): ParsedAgentCatalog {
    val agents = payload?.getAsJsonArray("agents")?.mapNotNull { element ->
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val identity = obj.get("identity")?.takeIf { it.isJsonObject }?.asJsonObject
        val identityName = identity?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
        val configuredName = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
        val emoji = identity?.get("emoji")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() && it != "(not set)" }
        var name = configuredName ?: identityName ?: id
        if (emoji != null && !name.contains(emoji)) name = "$emoji $name"
        val modelElement = obj.get("model")
        val model = when {
            modelElement == null -> null
            modelElement.isJsonPrimitive -> modelElement.asString
            modelElement.isJsonObject -> modelElement.asJsonObject.get("primary")
                ?.takeIf { it.isJsonPrimitive }?.asString
            else -> null
        }
        AgentInfo(id = id, name = name, model = model)
    }.orEmpty()
    val defaultAgentId = payload?.get("defaultId")?.takeIf { it.isJsonPrimitive }?.asString
    return ParsedAgentCatalog(agents, defaultAgentId)
}

internal fun parseConfiguredModels(payload: JsonObject?): List<ModelInfo> =
    payload?.getAsJsonArray("models")?.mapNotNull { element ->
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val provider = obj.get("provider")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val ref = if (id.startsWith("$provider/")) id else "$provider/$id"
        val alias = obj.get("alias")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
        val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
        val available = obj.get("available")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        ModelInfo(ref = ref, provider = provider, id = id, name = alias ?: name ?: ref, available = available)
    }.orEmpty()

internal fun resolveSessionModelRef(
    payload: JsonObject?,
    sessionKey: String,
    models: List<ModelInfo>,
): String? {
    val row = payload?.getAsJsonArray("sessions")?.firstOrNull { element ->
        element.isJsonObject && element.asJsonObject.get("key")
            ?.takeIf { it.isJsonPrimitive }?.asString == sessionKey
    }?.asJsonObject ?: return null
    val provider = row.get("modelProvider")?.takeIf { it.isJsonPrimitive }?.asString
        ?.takeIf { it.isNotBlank() } ?: return null
    val model = row.get("model")?.takeIf { it.isJsonPrimitive }?.asString
        ?.takeIf { it.isNotBlank() } ?: return null
    val candidate = if (model.startsWith("$provider/")) model else "$provider/$model"
    return models.firstOrNull { it.ref == candidate }?.ref
}
