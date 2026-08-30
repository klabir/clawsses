package com.clawsses.phone.openclaw

import com.clawsses.shared.ChatAttachment
import com.clawsses.shared.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedChatStoreTest {
    @Test
    fun `keeps newest messages within count budget`() {
        val store = BoundedChatStore(maxMessages = 3)
        store.replace((1..5).map { message("m$it") })
        assertEquals(listOf("m3", "m4", "m5"), store.value().map(ChatMessage::id))
    }

    @Test
    fun `streaming tail is replaced without entering completed history`() {
        val store = BoundedChatStore(maxMessages = 3)
        store.add(message("user"))
        store.updateStreaming("assistant", "a")
        store.updateStreaming("assistant", "abc")
        assertEquals(listOf("user", "assistant"), store.value().map(ChatMessage::id))
        assertEquals("abc", store.value().last().content)

        store.upsertCompleted(message("assistant", "complete"))
        assertEquals(2, store.value().size)
        assertEquals("complete", store.value().last().content)
    }

    @Test
    fun `newest attachments win bounded byte budget`() {
        val store = BoundedChatStore(maxAttachmentBytes = 3, maxAttachmentsPerMessage = 4)
        val old = message("old", attachments = listOf(attachment("AAAA")))
        val newest = message("new", attachments = listOf(attachment("BBBB")))
        store.replace(listOf(old, newest))

        assertTrue(store.value().first().attachments.isEmpty())
        assertEquals(1, store.value().last().attachments.size)
    }

    @Test
    fun `file backed attachment uses stored byte size`() {
        val store = BoundedChatStore(maxAttachmentBytes = 3)
        store.add(
            message(
                "file",
                attachments = listOf(ChatAttachment(localPath = "/owned", sizeBytes = 4)),
            ),
        )

        assertTrue(store.value().single().attachments.isEmpty())
    }

    @Test
    fun `history replacement discards stale streaming tail`() {
        val store = BoundedChatStore()
        store.updateStreaming("stale", "partial")
        store.replace(listOf(message("history")))
        assertFalse(store.value().any { it.id == "stale" })
    }

    @Test
    fun `canonical reconciliation replaces optimistic id without reordering`() {
        val store = BoundedChatStore()
        store.replace(listOf(message("before"), message("local"), message("after")))
        val result = store.reconcileCanonical(message("canonical"), replacingId = "local")
        assertTrue(result.changed)
        assertEquals(listOf("before", "canonical", "after"), result.messages.map(ChatMessage::id))
    }

    @Test
    fun `identical text with distinct canonical ids is retained`() {
        val store = BoundedChatStore()
        store.reconcileCanonical(message("one", "same"), replacingId = null)
        store.reconcileCanonical(message("two", "same"), replacingId = null)
        assertEquals(listOf("one", "two"), store.value().map(ChatMessage::id))
    }

    private fun message(
        id: String,
        content: String = id,
        attachments: List<ChatAttachment> = emptyList(),
    ) = ChatMessage(id = id, role = "assistant", content = content, attachments = attachments)

    private fun attachment(base64: String) = ChatAttachment(base64 = base64)
}
