package com.clawsses.phone.openclaw

import com.clawsses.shared.ChatAttachment
import com.clawsses.shared.ChatMessage
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.UUID

internal data class ParsedChatHistory(
    val messages: List<ChatMessage>,
    val rawCount: Int,
)

internal object OpenClawChatHistoryParser {
    private const val MAX_EMBEDDED_BASE64_CHARS = 12_000_000

    fun parse(sessionKey: String, source: JsonArray?): ParsedChatHistory {
        if (source == null) return ParsedChatHistory(emptyList(), 0)
        val messages = source.mapIndexedNotNull { rawIndex, element ->
            runCatching {
                val message = element.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@runCatching null
                val role = message.primitiveString("role")
                    ?.takeIf { it == "user" || it == "assistant" }
                    ?: return@runCatching null
                val (content, attachments) = parseContent(message.get("content"))
                if (content.isEmpty() && attachments.isEmpty()) return@runCatching null
                val timestamp = message.get("timestamp")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asLong ?: 0L
                val explicitId = listOf("id", "messageId", "clientMessageId")
                    .firstNotNullOfOrNull { name -> message.primitiveString(name) }
                    ?.takeIf(String::isNotBlank)
                ChatMessage(
                    id = stableHistoryMessageId(
                        sessionKey = sessionKey,
                        explicitId = explicitId,
                        role = role,
                        content = content,
                        timestamp = timestamp,
                        tailIndex = source.size() - rawIndex - 1,
                    ),
                    role = role,
                    content = content,
                    timestamp = timestamp,
                    attachments = attachments,
                )
            }.getOrNull()
        }
        return ParsedChatHistory(messages, source.size())
    }

    private fun parseContent(content: com.google.gson.JsonElement?): Pair<String, List<ChatAttachment>> =
        when {
            content == null -> "" to emptyList()
            content.isJsonPrimitive -> content.asString to emptyList()
            content.isJsonArray -> parseContentArray(content.asJsonArray)
            else -> "" to emptyList()
        }

    private fun parseContentArray(content: JsonArray): Pair<String, List<ChatAttachment>> {
        val text = StringBuilder()
        val attachments = mutableListOf<ChatAttachment>()
        content.forEach { element ->
            val block = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            when (block.primitiveString("type")) {
                "text", "input_text", "output_text" ->
                    block.primitiveString("text")?.let(text::append)
                "image", "input_image" -> parseEmbeddedImage(block)?.let(attachments::add)
            }
        }
        return text.toString() to attachments
    }

    private fun parseEmbeddedImage(block: JsonObject): ChatAttachment? {
        val embedded = listOfNotNull(
            block.primitiveString("base64"),
            block.primitiveString("content"),
            block.primitiveString("url"),
            block.nestedPrimitiveString("data", "url"),
            block.nestedPrimitiveString("image_url", "url"),
        ).firstOrNull { it.startsWith("data:image/") || !it.contains("://") } ?: return null
        val base64 = if (embedded.startsWith("data:")) embedded.substringAfter(',', "") else embedded
        if (base64.isEmpty() || base64.length > MAX_EMBEDDED_BASE64_CHARS) return null
        val dataMime = embedded.takeIf { it.startsWith("data:") }
            ?.substringAfter("data:")?.substringBefore(';')
        return ChatAttachment(
            mimeType = block.primitiveString("mimeType") ?: dataMime,
            fileName = block.primitiveString("fileName"),
            base64 = base64,
        )
    }

    private fun JsonObject.primitiveString(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.nestedPrimitiveString(objectName: String, valueName: String): String? =
        get(objectName)?.takeIf { it.isJsonObject }?.asJsonObject?.primitiveString(valueName)
}

internal data class HistoryPrependMerge(
    val combined: List<ChatMessage>,
    val prependedCount: Int,
) {
    companion object {
        fun merge(refetched: List<ChatMessage>, existing: List<ChatMessage>): HistoryPrependMerge {
            val existingIds = existing.mapTo(HashSet()) { it.id }
            val firstExistingIndex = refetched.indexOfFirst { it.id in existingIds }
            val older = when {
                firstExistingIndex >= 0 -> refetched.take(firstExistingIndex)
                existing.isNotEmpty() -> refetched.dropLast(existing.size.coerceAtMost(refetched.size))
                else -> refetched
            }
            return HistoryPrependMerge(older + existing, older.size)
        }
    }
}

internal fun stableHistoryMessageId(
    sessionKey: String,
    explicitId: String?,
    role: String,
    content: String,
    timestamp: Long,
    tailIndex: Int,
): String = explicitId?.takeIf { it.isNotBlank() } ?: UUID.nameUUIDFromBytes(
    "$sessionKey\u0000$role\u0000$timestamp\u0000$tailIndex\u0000$content"
        .toByteArray(Charsets.UTF_8),
).toString()
