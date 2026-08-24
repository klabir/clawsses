package com.clawsses.phone.openclaw

/** One coalesced streaming publication for the phone UI and glasses transport. */
internal data class PendingStreamUpdate(
    val messageId: String,
    val fullText: String,
    val chunk: String,
)

/**
 * Thread-safe accumulator that turns many gateway deltas into one bounded-rate publication.
 * Final delivery remains lossless because callers drain synchronously before completing a run.
 */
internal class StreamUpdateBuffer {
    private var messageId: String? = null
    private var latestFullText = ""
    private val pendingChunk = StringBuilder()
    private var publicationScheduled = false

    /** Returns true when the caller must schedule the next delayed drain. */
    @Synchronized
    fun offer(messageId: String, fullText: String, chunk: String): Boolean {
        if (this.messageId != null && this.messageId != messageId) resetLocked()
        this.messageId = messageId
        latestFullText = fullText
        pendingChunk.append(chunk)
        if (publicationScheduled) return false
        publicationScheduled = true
        return true
    }

    @Synchronized
    fun drain(): PendingStreamUpdate? {
        publicationScheduled = false
        val id = messageId ?: return null
        if (pendingChunk.isEmpty()) return null
        return PendingStreamUpdate(
            messageId = id,
            fullText = latestFullText,
            chunk = pendingChunk.toString(),
        ).also {
            pendingChunk.clear()
        }
    }

    @Synchronized
    fun reset() = resetLocked()

    private fun resetLocked() {
        messageId = null
        latestFullText = ""
        pendingChunk.clear()
        publicationScheduled = false
    }
}
