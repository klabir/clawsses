package com.clawsses.phone.openclaw

import com.clawsses.shared.ChatMessage
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawChatHistoryTest {
    @Test
    fun `parses supported roles text and embedded images`() {
        val source = JsonParser.parseString(
            """[
              {"id":"u1","role":"user","content":"hello","timestamp":1},
              {"role":"assistant","content":[
                {"type":"output_text","text":"answer"},
                {"type":"image","url":"data:image/webp;base64,QUJD"}
              ]},
              {"role":"system","content":"hidden"}
            ]""",
        ).asJsonArray
        val parsed = OpenClawChatHistoryParser.parse("session", source)
        assertEquals(3, parsed.rawCount)
        assertEquals(listOf("user", "assistant"), parsed.messages.map(ChatMessage::role))
        assertEquals("u1", parsed.messages.first().id)
        assertEquals("answer", parsed.messages.last().content)
        assertEquals("QUJD", parsed.messages.last().attachments.single().base64)
    }

    @Test
    fun `ignores remote images and malformed messages`() {
        val source = JsonParser.parseString(
            """[
              {"role":"assistant","content":[{"type":"image","url":"https://example.test/a.png"}]},
              null,
              {"role":"assistant","content":{}}
            ]""",
        ).asJsonArray
        assertTrue(OpenClawChatHistoryParser.parse("session", source).messages.isEmpty())
    }

    @Test
    fun `prepend merge reuses existing tail and adds only older prefix`() {
        val older = message("old")
        val current = listOf(message("a"), message("b"))
        val result = HistoryPrependMerge.merge(listOf(older) + current, current)
        assertEquals(1, result.prependedCount)
        assertEquals(listOf("old", "a", "b"), result.combined.map(ChatMessage::id))
    }

    private fun message(id: String) = ChatMessage(id = id, role = "assistant", content = id)
}
