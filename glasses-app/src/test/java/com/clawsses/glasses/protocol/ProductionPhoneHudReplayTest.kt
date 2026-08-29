package com.clawsses.glasses.protocol

import com.clawsses.glasses.state.HudStateEvent
import com.clawsses.glasses.state.HudStateReducer
import com.clawsses.glasses.state.HudHistorySnapshotAssembler
import com.clawsses.glasses.ui.ChatHudState
import com.clawsses.glasses.ui.DisplayMessage
import com.clawsses.glasses.ui.HudStreamingAccumulator
import com.clawsses.shared.ChatStream
import com.clawsses.shared.ChatStreamEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionPhoneHudReplayTest {
    @Test
    fun `one thousand deltas survive production serialization coalescing and finalization`() {
        val expected = StringBuilder()
        val accumulator = HudStreamingAccumulator()
        var state = ChatHudState(isScrolledToEnd = true)
        var publications = 0

        repeat(1_000) { index ->
            expected.append("token-$index ")
            if ((index + 1) % DELTAS_PER_PUBLICATION == 0) {
                val firstIndex = index + 1 - DELTAS_PER_PUBLICATION
                val wireChunk = (firstIndex..index).joinToString(separator = "") { "token-$it " }
                val decoded = decode(ChatStream(id = LIVE_ID, chunk = wireChunk).toJson())
                val stream = decoded as PhoneHudMessage.Stream
                accumulator.append(stream.id, stream.chunk)
                assertEquals(expected.toString(), accumulator.snapshotIfChanged()?.content)
                publications += 1
            }
        }

        val end = decode(ChatStreamEnd(id = LIVE_ID).toJson()) as PhoneHudMessage.StreamEnd
        val completed = requireNotNull(accumulator.finish(end.id))
        state = HudStateReducer.reduce(
            state,
            HudStateEvent.StreamCompleted(completed.id, completed.content),
        ).state

        assertEquals(100, publications)
        assertEquals(expected.toString(), state.messages.single().content)
        assertFalse(state.messages.single().isStreaming)
        assertFalse(accumulator.hasUnpublishedChanges())
    }

    @Test
    fun `history replacement after reconnect removes prior stream without duplicates`() {
        val historyJson = """{"type":"chat_history","hasMore":false,"messages":[
            {"i":"one","r":"u","c":"question"},
            {"i":"two","r":"a","c":"answer"}
        ]}""".trimIndent()
        val history = decode(historyJson) as PhoneHudMessage.History
        var state = ChatHudState(
            messages = listOf(DisplayMessage("stale", "assistant", "partial", isStreaming = true)),
        )

        state = HudStateReducer.reduce(
            state,
            HudStateEvent.HistoryLoaded(
                messages = history.messages.map { DisplayMessage(it.id, it.role, it.content) },
                isLoadMore = history.isLoadMore,
                hasMore = history.hasMore,
            ),
        ).state

        assertEquals(listOf("one", "two"), state.messages.map { it.id })
        assertTrue(state.messages.none { it.isStreaming })
        assertFalse(state.hasMoreHistory)
    }

    @Test
    fun `interleaved history packets accept only the newest production snapshot`() {
        val assembler = HudHistorySnapshotAssembler()
        val packets = listOf(
            """{"type":"chat_history_begin","s":"old","hasMore":false}""",
            """{"type":"chat_history_chunk","s":"old","i":"stale","r":"a","c":"old "}""",
            """{"type":"chat_history_begin","s":"new","hasMore":true}""",
            """{"type":"chat_history_chunk","s":"old","i":"stale","r":"a","c":"late"}""",
            """{"type":"chat_history_chunk","s":"new","i":"fresh","r":"a","c":"new "}""",
            """{"type":"chat_history_chunk","s":"new","i":"fresh","r":"a","c":"answer"}""",
            """{"type":"chat_history_end","s":"old"}""",
            """{"type":"chat_history_end","s":"new"}""",
        )
        var completedContent: String? = null

        packets.map(::decode).forEach { message ->
            when (message) {
                is PhoneHudMessage.HistoryBegin -> assembler.begin(
                    message.snapshotId,
                    message.isLoadMore,
                    message.hasMore,
                )
                is PhoneHudMessage.HistoryChunk -> assembler.append(
                    message.snapshotId,
                    message.id,
                    message.role,
                    message.content,
                )
                is PhoneHudMessage.HistoryEnd -> assembler.finish(message.snapshotId)?.let {
                    completedContent = it.messages.single().content
                    assertTrue(it.hasMore)
                }
                else -> error("Unexpected replay message: $message")
            }
        }

        assertEquals("new answer", completedContent)
    }

    private fun decode(raw: String): PhoneHudMessage =
        (PhoneHudMessageCodec.decode(raw) as PhoneHudDecodeResult.Success).envelope.message

    private companion object {
        const val LIVE_ID = "live-answer"
        const val DELTAS_PER_PUBLICATION = 10
    }
}
