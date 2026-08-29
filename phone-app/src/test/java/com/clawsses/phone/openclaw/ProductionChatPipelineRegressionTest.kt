package com.clawsses.phone.openclaw

import com.clawsses.shared.ChatMessage
import com.clawsses.shared.ChatStream
import com.clawsses.shared.CxrPayloadLimits
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionChatPipelineRegressionTest {
    @Test
    fun `gateway history and one thousand deltas produce bounded lossless wire output`() {
        val source = gatewayHistory(500)
        val parsed = OpenClawChatHistoryParser.parse(SESSION, source)
        val store = BoundedChatStore()
        store.replace(parsed.messages)

        val buffer = StreamUpdateBuffer()
        val expected = StringBuilder()
        val wirePackets = mutableListOf<String>()
        var scheduledPublications = 0

        repeat(1_000) { index ->
            val chunk = "token-$index "
            expected.append(chunk)
            if (buffer.offer(LIVE_ID, expected.toString(), chunk)) {
                scheduledPublications += 1
            }
            if ((index + 1) % DELTAS_PER_PUBLICATION == 0) {
                val update = requireNotNull(buffer.drain())
                store.updateStreaming(update.messageId, update.fullText)
                wirePackets += ChatStream(id = update.messageId, chunk = update.chunk).toJson()
            }
        }

        val completed = ChatMessage(id = LIVE_ID, role = "assistant", content = expected.toString())
        store.upsertCompleted(completed)
        val historyPage = GlassesChatHistoryPage.latest(store.value(), gatewayHasMore = false)
        val historyPackets = GlassesChatHistoryPage.buildPackets(
            historyPage.messages,
            hasMore = historyPage.hasMore,
        )

        assertEquals(100, scheduledPublications)
        assertEquals(100, wirePackets.size)
        assertEquals(expected.toString(), wirePackets.joinToString("") { packet ->
            ChatStream.fromJson(packet).chunk
        })
        assertEquals(BoundedChatStore.DEFAULT_MAX_MESSAGES, store.value().size)
        assertEquals(completed, store.value().last())
        assertEquals(GlassesChatHistoryPage.MAX_MESSAGES, historyPage.messages.size)
        assertTrue(historyPage.hasMore)
        assertTrue(historyPackets.all(CxrPayloadLimits::fits))
    }

    @Test
    fun `session replacement discards old tail and rejects delayed history operation`() {
        val store = BoundedChatStore()
        val operations = SessionOperationEpoch()
        val oldOperation = operations.begin()
        store.replace(listOf(ChatMessage(id = "old", role = "assistant", content = "old session")))
        store.updateStreaming("old-live", "stale partial")

        val currentOperation = operations.begin()
        store.replace(listOf(ChatMessage(id = "new", role = "assistant", content = "new session")))

        assertFalse(operations.isCurrent(oldOperation))
        assertTrue(operations.isCurrent(currentOperation))
        assertEquals(listOf("new"), store.value().map(ChatMessage::id))
    }

    private fun gatewayHistory(count: Int): JsonArray = JsonArray().apply {
        repeat(count) { index ->
            add(JsonObject().apply {
                addProperty("id", "history-$index")
                addProperty("role", if (index % 2 == 0) "assistant" else "user")
                addProperty("content", "message $index ${"word ".repeat(20)}")
                addProperty("timestamp", index)
            })
        }
    }

    private companion object {
        const val SESSION = "agent:main:main"
        const val LIVE_ID = "live-answer"
        const val DELTAS_PER_PUBLICATION = 10
    }
}
