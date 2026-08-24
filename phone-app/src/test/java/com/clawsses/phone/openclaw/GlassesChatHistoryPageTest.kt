package com.clawsses.phone.openclaw

import com.clawsses.shared.ChatMessage
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesChatHistoryPageTest {
    @Test
    fun `history snapshot stays within CXR limit and keeps recent messages`() {
        val messages = (1..16).map { index ->
            ChatMessage(
                id = "message-$index-12345678-1234-1234-1234-123456789abc",
                role = if (index % 2 == 0) "assistant" else "user",
                content = "Long content $index ".repeat(80),
            )
        }
        val payload = GlassesChatHistoryPage.build(messages)
        val parsed = JsonParser.parseString(payload).asJsonObject
        val page = parsed.getAsJsonArray("messages")

        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= GlassesChatHistoryPage.MAX_CXR_BYTES)
        assertEquals("chat_history", parsed["type"].asString)
        assertFalse(parsed["hasMore"].asBoolean)
        assertTrue(page.size() in 1..GlassesChatHistoryPage.MAX_MESSAGES)
        assertEquals("message-16-12345678-1234-1234-1234-123456789abc", page[page.size() - 1].asJsonObject["i"].asString)
    }

    @Test
    fun `utf8 truncation never splits a code point`() {
        val value = "ä🙂漢字".repeat(20)
        val truncated = GlassesChatHistoryPage.truncateUtf8(value, 17)
        assertTrue(truncated.toByteArray(Charsets.UTF_8).size <= 17)
        assertTrue(value.startsWith(truncated))
    }
}
