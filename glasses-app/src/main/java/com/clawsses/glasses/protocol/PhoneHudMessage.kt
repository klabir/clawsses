package com.clawsses.glasses.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

sealed interface PhoneHudMessage {
    data class CompletedMessage(
        val id: String,
        val role: String,
        val content: String,
        val thumbnails: List<Thumbnail>,
    ) : PhoneHudMessage

    data class History(
        val messages: List<CompletedMessage>,
        val isLoadMore: Boolean,
        val hasMore: Boolean,
    ) : PhoneHudMessage

    data class HistoryBegin(
        val snapshotId: String,
        val isLoadMore: Boolean,
        val hasMore: Boolean,
    ) : PhoneHudMessage

    data class HistoryChunk(
        val snapshotId: String,
        val id: String,
        val role: String,
        val content: String,
    ) : PhoneHudMessage

    data class HistoryEnd(val snapshotId: String) : PhoneHudMessage
    data class AgentPhase(val phase: String) : PhoneHudMessage
    data class AgentProgress(
        val id: String,
        val kind: String,
        val label: String,
        val state: String,
    ) : PhoneHudMessage

    data class Stream(val id: String, val chunk: String) : PhoneHudMessage
    data class StreamEnd(val id: String) : PhoneHudMessage
    data class Connection(
        val connected: Boolean,
        val sessionId: String?,
        val sessionName: String?,
    ) : PhoneHudMessage

    data class RunState(val state: String, val canAbort: Boolean) : PhoneHudMessage

    data class SessionList(
        val sessions: List<Session>,
        val currentSessionKey: String?,
        val nextOffset: Int?,
    ) : PhoneHudMessage

    data class Session(
        val key: String,
        val name: String,
        val kind: String?,
        val hasUnread: Boolean,
        val updatedAt: Long?,
    )

    data class SessionOperation(val operation: String, val state: String, val error: String?) :
        PhoneHudMessage

    data class ModelPage(
        val models: List<Model>,
        val catalogId: String?,
        val currentIndex: Int?,
        val offset: Int,
        val nextOffset: Int?,
        val pageIndex: Int,
        val pageCount: Int,
        val error: String?,
    ) : PhoneHudMessage

    data class Model(
        val index: Int,
        val name: String,
        val provider: String,
        val available: Boolean,
    )

    data class ModelOperation(val state: String, val currentIndex: Int?, val error: String?) :
        PhoneHudMessage

    data class AgentList(val agents: List<Agent>, val currentAgentId: String?) : PhoneHudMessage
    data class Agent(val id: String, val name: String, val model: String?)
    data class VoiceState(val state: String, val text: String, val mode: String?) : PhoneHudMessage
    data class VoiceResult(
        val resultType: String,
        val text: String,
        val autoSent: Boolean,
    ) : PhoneHudMessage

    data class PhotoResult(val status: String, val thumbnail: Thumbnail?) : PhoneHudMessage
    data class RemovePhoto(val all: Boolean, val index: Int?) : PhoneHudMessage
    data class WakeSignal(val reason: String, val bufferedCount: Int) : PhoneHudMessage
    data class TtsState(
        val enabled: Boolean,
        val voiceName: String?,
        val playbackState: String,
        val canReplay: Boolean,
    ) : PhoneHudMessage

    data class TalkModeState(val enabled: Boolean, val phase: String) : PhoneHudMessage
    data class HudCard(
        val id: String,
        val source: String,
        val title: String,
        val body: String,
        val priority: String,
        val expiresAt: Long?,
        val actions: List<HudCardAction>,
    ) : PhoneHudMessage

    data class HudCardAction(val id: String, val label: String)
    data class LiveCaption(
        val enabled: Boolean,
        val sourceText: String,
        val translatedText: String?,
        val sourceLanguage: String?,
        val targetLanguage: String?,
        val error: String?,
    ) : PhoneHudMessage

    data class PeerState(
        val versionName: String,
        val versionCode: Int,
        val protocolVersion: Int,
        val capabilities: Set<String>,
    ) : PhoneHudMessage

    data class Thumbnail(
        val encoded: String,
        val format: String?,
        val width: Int,
        val height: Int,
    )
}

data class PhoneHudEnvelope(
    val transactionId: String?,
    val message: PhoneHudMessage,
)

sealed interface PhoneHudDecodeResult {
    data class Success(val envelope: PhoneHudEnvelope) : PhoneHudDecodeResult
    data class UnknownType(val type: String, val transactionId: String?) : PhoneHudDecodeResult
    data class Malformed(val type: String?, val transactionId: String?, val reason: String) :
        PhoneHudDecodeResult
}

object PhoneHudMessageCodec {
    fun decode(raw: String): PhoneHudDecodeResult {
        val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse {
            return PhoneHudDecodeResult.Malformed(null, null, "invalid JSON")
        }
        val type = json.string("type")
        val tx = json.optionalString("_tx")
        if (type == null) return PhoneHudDecodeResult.Malformed(null, tx, "missing type")
        val message = runCatching { decode(type, json) }.getOrElse { error ->
            return PhoneHudDecodeResult.Malformed(type, tx, error.message ?: "invalid payload")
        } ?: return PhoneHudDecodeResult.UnknownType(type, tx)
        return PhoneHudDecodeResult.Success(PhoneHudEnvelope(tx, message))
    }

    private fun decode(type: String, json: JsonObject): PhoneHudMessage? = when (type) {
        "chat_message" -> json.completedMessage()
        "chat_history" -> PhoneHudMessage.History(
            messages = json.array("messages").mapObjects { it.completedMessage() },
            isLoadMore = json.booleanOrDefault("isLoadMore", false),
            hasMore = json.booleanOrDefault("hasMore", true),
        )
        "chat_history_begin" -> PhoneHudMessage.HistoryBegin(
            snapshotId = json.requiredString("s"),
            isLoadMore = json.booleanOrDefault("isLoadMore", false),
            hasMore = json.booleanOrDefault("hasMore", false),
        )
        "chat_history_chunk" -> PhoneHudMessage.HistoryChunk(
            snapshotId = json.requiredString("s"),
            id = json.requiredString("i"),
            role = json.compactRole("r"),
            content = json.stringOrDefault("c", ""),
        )
        "chat_history_end" -> PhoneHudMessage.HistoryEnd(json.requiredString("s"))
        "agent_thinking" -> PhoneHudMessage.AgentPhase(json.stringOrDefault("phase", "thinking"))
        "agent_progress" -> PhoneHudMessage.AgentProgress(
            id = json.requiredString("id"),
            kind = json.stringOrDefault("kind", "status"),
            label = json.stringOrDefault("label", ""),
            state = json.stringOrDefault("state", "active"),
        )
        "chat_stream" -> PhoneHudMessage.Stream(
            id = json.requiredString("id"),
            chunk = json.stringOrDefault("chunk", ""),
        )
        "chat_stream_end" -> PhoneHudMessage.StreamEnd(json.requiredString("id"))
        "connection_update" -> PhoneHudMessage.Connection(
            connected = json.requiredBoolean("connected"),
            sessionId = json.optionalString("sessionId"),
            sessionName = json.optionalString("sessionName"),
        )
        "run_state" -> PhoneHudMessage.RunState(
            state = json.requiredString("state"),
            canAbort = json.requiredBoolean("canAbort"),
        )
        "session_list" -> {
            val unread = json.optionalArray("unreadSessionKeys")?.map { item ->
                item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    ?: error("unreadSessionKeys entries must be strings")
            }?.toSet().orEmpty()
            val hasMore = json.booleanOrDefault("hasMore", false)
            PhoneHudMessage.SessionList(
                sessions = json.array("sessions").mapObjects { session ->
                    val key = session.requiredString("k", "key")
                    PhoneHudMessage.Session(
                        key = key,
                        name = session.optionalString("n")
                            ?: session.optionalString("label")
                            ?: session.optionalString("displayName")
                            ?: session.optionalString("derivedTitle")
                            ?: key,
                        kind = session.optionalString("kind"),
                        hasUnread = session.optionalBoolean("u") ?: (key in unread),
                        updatedAt = session.optionalLong("updatedAt")?.takeIf { it > 0L },
                    )
                },
                currentSessionKey = json.optionalString("currentSessionKey"),
                nextOffset = json.optionalInt("nextOffset")?.takeIf { hasMore && it >= 0 },
            )
        }
        "session_operation" -> PhoneHudMessage.SessionOperation(
            operation = json.stringOrDefault("operation", ""),
            state = json.requiredString("state"),
            error = json.optionalString("error"),
        )
        "model_page" -> PhoneHudMessage.ModelPage(
            models = json.array("m").mapObjects { model ->
                PhoneHudMessage.Model(
                    index = model.requiredInt("i"),
                    name = model.stringOrDefault("n", "Model"),
                    provider = model.stringOrDefault("p", ""),
                    available = model.booleanOrDefault("a", true),
                )
            }.filter { it.index >= 0 },
            catalogId = json.optionalString("c"),
            currentIndex = json.optionalInt("ci")?.takeIf { it >= 0 },
            offset = json.intOrDefault("o", 0),
            nextOffset = json.optionalInt("x")?.takeIf { it >= 0 },
            pageIndex = json.intOrDefault("pi", 0),
            pageCount = json.intOrDefault("pc", 1).coerceAtLeast(1),
            error = json.optionalString("e"),
        )
        "model_operation" -> PhoneHudMessage.ModelOperation(
            state = json.requiredString("state"),
            currentIndex = json.optionalInt("ci")?.takeIf { it >= 0 },
            error = json.optionalString("error"),
        )
        "agent_list" -> PhoneHudMessage.AgentList(
            agents = json.array("agents").mapObjects { agent ->
                val id = agent.requiredString("id")
                PhoneHudMessage.Agent(
                    id = id,
                    name = agent.optionalString("name") ?: id,
                    model = agent.optionalString("model"),
                )
            },
            currentAgentId = json.optionalString("currentAgentId"),
        )
        "voice_state" -> PhoneHudMessage.VoiceState(
            state = json.stringOrDefault("state", ""),
            text = json.stringOrDefault("text", ""),
            mode = json.optionalString("mode"),
        )
        "voice_result" -> PhoneHudMessage.VoiceResult(
            resultType = json.stringOrDefault("result_type", "text"),
            text = json.stringOrDefault("text", ""),
            autoSent = json.booleanOrDefault("autoSent", false),
        )
        "photo_result" -> PhoneHudMessage.PhotoResult(
            status = json.stringOrDefault("status", ""),
            thumbnail = json.optionalString("thumbnail")?.let { encoded ->
                PhoneHudMessage.Thumbnail(
                    encoded = encoded,
                    format = json.optionalString("thumbnailFormat"),
                    width = json.intOrDefault("thumbnailWidth", 0),
                    height = json.intOrDefault("thumbnailHeight", 0),
                )
            },
        )
        "remove_photo" -> PhoneHudMessage.RemovePhoto(
            all = json.booleanOrDefault("all", false),
            index = json.optionalInt("index"),
        )
        "wake_signal" -> PhoneHudMessage.WakeSignal(
            reason = json.stringOrDefault("reason", ""),
            bufferedCount = json.intOrDefault("bufferedCount", 0),
        )
        "tts_state" -> PhoneHudMessage.TtsState(
            enabled = json.booleanOrDefault("enabled", false),
            voiceName = json.optionalString("voiceName"),
            playbackState = json.stringOrDefault("playbackState", "idle"),
            canReplay = json.booleanOrDefault("canReplay", false),
        )
        "talk_mode_state" -> PhoneHudMessage.TalkModeState(
            enabled = json.booleanOrDefault("enabled", false),
            phase = json.stringOrDefault("phase", "off"),
        )
        "hud_card" -> PhoneHudMessage.HudCard(
            id = json.stringOrDefault("id", ""),
            source = json.stringOrDefault("source", "Clawsses"),
            title = json.stringOrDefault("title", "Update"),
            body = json.stringOrDefault("body", ""),
            priority = json.stringOrDefault("priority", "normal"),
            expiresAt = json.optionalLong("expiresAt")?.takeIf { it > 0L },
            actions = json.array("actions").mapObjects { action ->
                PhoneHudMessage.HudCardAction(
                    id = action.stringOrDefault("id", ""),
                    label = action.stringOrDefault("label", ""),
                )
            }.filter { it.id.isNotBlank() && it.label.isNotBlank() },
        )
        "live_caption" -> PhoneHudMessage.LiveCaption(
            enabled = json.booleanOrDefault("enabled", false),
            sourceText = json.stringOrDefault("sourceText", ""),
            translatedText = json.optionalString("translatedText"),
            sourceLanguage = json.optionalString("sourceLanguage"),
            targetLanguage = json.optionalString("targetLanguage"),
            error = json.optionalString("error"),
        )
        "peer_state" -> PhoneHudMessage.PeerState(
            versionName = json.requiredString("versionName"),
            versionCode = json.requiredInt("versionCode"),
            protocolVersion = json.requiredInt("protocolVersion"),
            capabilities = json.optionalArray("capabilities")?.map { item ->
                item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    ?: error("capabilities entries must be strings")
            }?.toSet().orEmpty(),
        )
        else -> null
    }

    private fun JsonObject.completedMessage(): PhoneHudMessage.CompletedMessage {
        val compactRole = optionalString("r")
        return PhoneHudMessage.CompletedMessage(
            id = requiredString("i", "id"),
            role = when (compactRole) {
                "u" -> "user"
                "a" -> "assistant"
                else -> stringOrDefault("role", "assistant")
            },
            content = stringOrDefault("c", stringOrDefault("content", "")),
            thumbnails = optionalArray("attachments")?.mapObjects { attachment ->
                PhoneHudMessage.Thumbnail(
                    encoded = attachment.requiredString("thumbnail"),
                    format = attachment.optionalString("thumbnailFormat"),
                    width = attachment.intOrDefault("thumbnailWidth", 0),
                    height = attachment.intOrDefault("thumbnailHeight", 0),
                )
            }.orEmpty(),
        )
    }

    private fun JsonObject.compactRole(key: String): String = when (stringOrDefault(key, "a")) {
        "u" -> "user"
        else -> "assistant"
    }

    private fun JsonObject.requiredString(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> optionalString(key) }
            ?: error("missing ${keys.joinToString("/")}")

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }
        ?.asJsonPrimitive?.takeIf { it.isString }?.asString

    private fun JsonObject.optionalString(key: String): String? =
        string(key)?.takeIf(String::isNotBlank)

    private fun JsonObject.stringOrDefault(key: String, default: String): String =
        if (!has(key) || get(key).isJsonNull) default else string(key) ?: error("$key must be a string")

    private fun JsonObject.requiredBoolean(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            ?.takeIf { it.isBoolean }?.asBoolean ?: error("$key must be a boolean")

    private fun JsonObject.requiredInt(key: String): Int =
        get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            ?.takeIf { it.isNumber }?.asInt ?: error("$key must be a number")

    private fun JsonObject.optionalBoolean(key: String): Boolean? =
        if (!has(key) || get(key).isJsonNull) null else requiredBoolean(key)

    private fun JsonObject.optionalInt(key: String): Int? =
        if (!has(key) || get(key).isJsonNull) null else requiredInt(key)

    private fun JsonObject.optionalLong(key: String): Long? =
        if (!has(key) || get(key).isJsonNull) null else get(key).asJsonPrimitive
            .takeIf { it.isNumber }?.asLong ?: error("$key must be a number")

    private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
        if (!has(key) || get(key).isJsonNull) default else requiredBoolean(key)

    private fun JsonObject.intOrDefault(key: String, default: Int): Int =
        if (!has(key) || get(key).isJsonNull) default else get(key).asJsonPrimitive
            .takeIf { it.isNumber }?.asInt ?: error("$key must be a number")

    private fun JsonObject.array(key: String): JsonArray = optionalArray(key) ?: JsonArray()

    private fun JsonObject.optionalArray(key: String): JsonArray? = get(key)?.let { element ->
        if (element.isJsonNull) null else element.takeIf { it.isJsonArray }?.asJsonArray
            ?: error("$key must be an array")
    }

    private fun <T> JsonArray.mapObjects(transform: (JsonObject) -> T): List<T> =
        map { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject ?: error("array item must be an object")
        }.map(transform)
}
