package com.clawsses.phone.openclaw

import com.clawsses.shared.ChatAttachment
import com.clawsses.shared.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded in-memory chat history plus one independently owned streaming tail. */
internal class BoundedChatStore(
    private val maxMessages: Int = DEFAULT_MAX_MESSAGES,
    private val maxAttachmentBytes: Long = DEFAULT_MAX_ATTACHMENT_BYTES,
    private val maxAttachmentsPerMessage: Int = DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE,
) {
    init {
        require(maxMessages > 0)
        require(maxAttachmentBytes >= 0)
        require(maxAttachmentsPerMessage >= 0)
    }

    private var completed: List<ChatMessage> = emptyList()
    private var streamingTail: ChatMessage? = null
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    @Synchronized
    fun value(): List<ChatMessage> = _messages.value

    @Synchronized
    fun clear() {
        completed = emptyList()
        streamingTail = null
        publish()
    }

    @Synchronized
    fun replace(messages: List<ChatMessage>): List<ChatMessage> {
        completed = applyBudgets(messages)
        streamingTail = null
        publish()
        return _messages.value
    }

    @Synchronized
    fun add(message: ChatMessage): List<ChatMessage> {
        completed = applyBudgets(completed + message)
        publish()
        return _messages.value
    }

    @Synchronized
    fun upsertCompleted(message: ChatMessage): List<ChatMessage> {
        streamingTail = streamingTail?.takeUnless { it.id == message.id }
        val index = completed.indexOfFirst { it.id == message.id }
        completed = if (index >= 0) {
            completed.toMutableList().also { it[index] = message }
        } else {
            completed + message
        }
        completed = applyBudgets(completed)
        publish()
        return _messages.value
    }

    @Synchronized
    fun updateStreaming(messageId: String, fullText: String): List<ChatMessage> {
        streamingTail = ChatMessage(id = messageId, role = "assistant", content = fullText)
        publish()
        return _messages.value
    }

    @Synchronized
    fun discardStreamingTail() {
        streamingTail = null
        publish()
    }

    private fun applyBudgets(messages: List<ChatMessage>): List<ChatMessage> {
        val bounded = messages.takeLast(maxMessages)
        var remainingBytes = maxAttachmentBytes
        val reversed = ArrayList<ChatMessage>(bounded.size)
        for (message in bounded.asReversed()) {
            val retained = ArrayList<ChatAttachment>()
            for (attachment in message.attachments.take(maxAttachmentsPerMessage)) {
                val bytes = attachment.base64?.let(::estimatedDecodedBytes) ?: 0L
                if (bytes <= remainingBytes) {
                    retained += attachment
                    remainingBytes -= bytes
                }
            }
            reversed += if (retained == message.attachments) message
            else message.copy(attachments = retained)
        }
        reversed.reverse()
        return reversed
    }

    private fun publish() {
        _messages.value = streamingTail?.let { completed + it } ?: completed
    }

    private fun estimatedDecodedBytes(base64: String): Long =
        ((base64.length.toLong() + 3L) / 4L) * 3L

    companion object {
        const val DEFAULT_MAX_MESSAGES = 500
        const val DEFAULT_MAX_ATTACHMENT_BYTES = 16L * 1024L * 1024L
        const val DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE = 4
    }
}
