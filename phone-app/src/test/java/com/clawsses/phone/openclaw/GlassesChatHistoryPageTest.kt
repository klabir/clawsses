package com.clawsses.phone.openclaw

import com.clawsses.shared.ChatMessage
import com.clawsses.shared.CxrPayloadLimits
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesChatHistoryPageTest {
    @Test
    fun `history snapshot keeps complete recent messages in bounded packets`() {
        val messages = (1..16).map { index ->
            ChatMessage(
                id = "message-$index-12345678-1234-1234-1234-123456789abc",
                role = if (index % 2 == 0) "assistant" else "user",
                content = "Long content $index with ä🙂漢字 and a quote \" ".repeat(80),
            )
        }

        val packets = GlassesChatHistoryPage.buildPackets(messages)
        val parsed = packets.map { JsonParser.parseString(it).asJsonObject }
        val reconstructed = linkedMapOf<String, StringBuilder>()

        parsed.filter { it["type"].asString == "chat_history_chunk" }.forEach { chunk ->
            reconstructed.getOrPut(chunk["i"].asString, ::StringBuilder)
                .append(chunk["c"].asString)
        }

        assertEquals("chat_history_begin", parsed.first()["type"].asString)
        assertEquals("chat_history_end", parsed.last()["type"].asString)
        assertTrue(packets.all { it.toByteArray(Charsets.UTF_8).size <= GlassesChatHistoryPage.MAX_CXR_BYTES })
        assertEquals(messages.takeLast(GlassesChatHistoryPage.MAX_MESSAGES).map { it.id }, reconstructed.keys.toList())
        messages.takeLast(GlassesChatHistoryPage.MAX_MESSAGES).forEach { message ->
            assertEquals(message.content, reconstructed[message.id].toString())
        }
    }

    @Test
    fun `empty history still forms a complete atomic snapshot`() {
        val packets = GlassesChatHistoryPage.buildPackets(emptyList())
        val types = packets.map { JsonParser.parseString(it).asJsonObject["type"].asString }

        assertEquals(listOf("chat_history_begin", "chat_history_end"), types)
    }

    @Test
    fun `history packets reserve room for worst case acknowledgement metadata`() {
        val message = ChatMessage(
            id = "message-12345678-1234-1234-1234-123456789abc",
            role = "assistant",
            content = "ä🙂漢字 long response ".repeat(200),
        )
        val reserve = 48
        val packets = GlassesChatHistoryPage.buildPackets(
            listOf(message),
            maxBytes = CxrPayloadLimits.MAX_BYTES - reserve,
        )
        val worstCaseTransaction = "z".repeat(13) + "-" + "z".repeat(13)

        packets.forEach { packet ->
            val wire = JsonParser.parseString(packet).asJsonObject.apply {
                addProperty("_tx", worstCaseTransaction)
            }.toString()
            assertTrue(CxrPayloadLimits.fits(wire))
        }
    }
}
