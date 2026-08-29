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
