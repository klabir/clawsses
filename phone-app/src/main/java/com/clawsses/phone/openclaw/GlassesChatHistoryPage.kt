package com.clawsses.phone.openclaw

import com.clawsses.shared.ChatMessage
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Builds a recent-history snapshot that fits in one Rokid CXR custom command. */
object GlassesChatHistoryPage {
    const val MAX_CXR_BYTES = 500
    const val MAX_MESSAGES = 3
    const val MAX_CONTENT_BYTES = 64

    fun build(messages: List<ChatMessage>, maxBytes: Int = MAX_CXR_BYTES): String {
        require(maxBytes > 0)
        val selected = messages.takeLast(MAX_MESSAGES).map { message ->
            JsonObject().apply {
                addProperty("i", message.id)
                addProperty("r", message.role.take(1))
                addProperty("c", truncateUtf8(message.content, MAX_CONTENT_BYTES))
            }
        }.toMutableList()

        while (true) {
            val payload = JsonObject().apply {
                addProperty("type", "chat_history")
                addProperty("hasMore", false)
                add("messages", JsonArray().also { array -> selected.forEach(array::add) })
            }.toString()
            if (payload.toByteArray(Charsets.UTF_8).size <= maxBytes || selected.isEmpty()) return payload
            selected.removeAt(0)
        }
    }

    internal fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val result = StringBuilder()
        var used = 0
        val iterator = value.codePoints().iterator()
        while (iterator.hasNext()) {
            val text = String(Character.toChars(iterator.nextInt()))
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (used + bytes > maxBytes) break
            result.append(text)
            used += bytes
        }
        return result.toString()
    }
}
