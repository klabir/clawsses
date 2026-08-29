package com.clawsses.phone.openclaw

import com.clawsses.shared.ChatMessage
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class Build90IntegrationBudgetTest {
    @Test
    fun `history parse and one thousand tail updates stay bounded`() {
        val source = JsonArray().apply {
            repeat(500) { index ->
                add(JsonObject().apply {
                    addProperty("id", "message-$index")
                    addProperty("role", if (index % 2 == 0) "assistant" else "user")
                    addProperty("content", "Message $index ${"word ".repeat(20)}")
                    addProperty("timestamp", index)
                })
            }
        }
        val store = BoundedChatStore()
        val elapsedMs = measureTimeMillis {
            val parsed = OpenClawChatHistoryParser.parse("benchmark-session", source)
            store.replace(parsed.messages)
            repeat(1_000) { update ->
                store.updateStreaming("live", "token ".repeat(update % 120))
            }
            store.upsertCompleted(
                ChatMessage(id = "live", role = "assistant", content = "complete"),
            )
        }

        assertTrue("Integration workload took ${elapsedMs}ms", elapsedMs < 2_000)
        assertEquals(BoundedChatStore.DEFAULT_MAX_MESSAGES, store.value().size)
        assertEquals("live", store.value().last().id)
        assertEquals("complete", store.value().last().content)
    }
}
