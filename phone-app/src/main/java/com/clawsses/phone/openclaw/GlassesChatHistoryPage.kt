package com.clawsses.phone.openclaw

import com.clawsses.shared.ChatMessage
import com.clawsses.shared.CxrPayloadLimits
import com.google.gson.JsonObject
import java.util.concurrent.atomic.AtomicLong

/** Builds an atomic recent-history snapshot split across valid Rokid CXR commands. */
object GlassesChatHistoryPage {
    const val MAX_CXR_BYTES = CxrPayloadLimits.MAX_BYTES
    const val MAX_MESSAGES = 3

    private val nextSnapshotId = AtomicLong()

    data class Page(val messages: List<ChatMessage>, val hasMore: Boolean)

    fun latest(messages: List<ChatMessage>, gatewayHasMore: Boolean): Page = Page(
        messages = messages.takeLast(MAX_MESSAGES),
        hasMore = gatewayHasMore || messages.size > MAX_MESSAGES,
    )

    fun before(
        messages: List<ChatMessage>,
        beforeMessageId: String,
        gatewayHasMore: Boolean,
    ): Page? {
        val end = messages.indexOfFirst { it.id == beforeMessageId }
        if (end < 0) return null
        val start = (end - MAX_MESSAGES).coerceAtLeast(0)
        return Page(
            messages = messages.subList(start, end),
            hasMore = gatewayHasMore || start > 0,
        )
    }

    fun buildPackets(
        messages: List<ChatMessage>,
        maxBytes: Int = MAX_CXR_BYTES,
        isLoadMore: Boolean = false,
        hasMore: Boolean = false,
    ): List<String> {
        require(maxBytes > 0)
        val snapshotId = nextSnapshotId.incrementAndGet().toString(36)
        val packets = mutableListOf(
            JsonObject().apply {
                addProperty("type", "chat_history_begin")
                addProperty("s", snapshotId)
                addProperty("hasMore", hasMore)
                addProperty("isLoadMore", isLoadMore)
            }.toString()
        )

        messages.forEach { message ->
            packets += buildMessagePackets(snapshotId, message, maxBytes)
        }
        packets += JsonObject().apply {
            addProperty("type", "chat_history_end")
            addProperty("s", snapshotId)
        }.toString()

        packets.forEach { packet ->
            require(CxrPayloadLimits.byteSize(packet) <= maxBytes) {
                "History packet exceeds CXR limit: ${CxrPayloadLimits.byteSize(packet)} bytes"
            }
        }
        return packets
    }

    private fun buildMessagePackets(
        snapshotId: String,
        message: ChatMessage,
        maxBytes: Int,
    ): List<String> {
        val packets = mutableListOf<String>()
        var charOffset = 0

        do {
            val remainingCodePoints = message.content.codePointCount(charOffset, message.content.length)
            var low = if (remainingCodePoints == 0) 0 else 1
            var high = remainingCodePoints
            var bestPacket: String? = null
            var bestEnd = charOffset

            while (low <= high) {
                val count = (low + high) ushr 1
                val end = message.content.offsetByCodePoints(charOffset, count)
                val packet = buildChunkPacket(
                    snapshotId = snapshotId,
                    message = message,
                    content = message.content.substring(charOffset, end),
                )
                if (CxrPayloadLimits.byteSize(packet) <= maxBytes) {
                    bestPacket = packet
                    bestEnd = end
                    low = count + 1
                } else {
                    high = count - 1
                }
            }

            if (remainingCodePoints == 0) {
                bestPacket = buildChunkPacket(snapshotId, message, "")
            }
            require(bestPacket != null) {
                "CXR limit is too small for a history chunk header"
            }
            packets += bestPacket
            charOffset = bestEnd
        } while (charOffset < message.content.length)

        return packets
    }

    private fun buildChunkPacket(
        snapshotId: String,
        message: ChatMessage,
        content: String,
    ): String = JsonObject().apply {
        addProperty("type", "chat_history_chunk")
        addProperty("s", snapshotId)
        addProperty("i", message.id)
        addProperty("r", message.role.take(1))
        addProperty("c", content)
    }.toString()
}
