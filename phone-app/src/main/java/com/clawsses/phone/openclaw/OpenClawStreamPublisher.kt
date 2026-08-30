package com.clawsses.phone.openclaw

/** Owns bounded-rate publication scheduling while the client retains coroutine ownership. */
internal class OpenClawStreamPublisher(
    private val publicationIntervalMs: Long,
    private val schedule: (delayMs: Long, publication: () -> Unit) -> Unit,
    private val publish: (PendingStreamUpdate) -> Unit,
    private val buffer: StreamUpdateBuffer = StreamUpdateBuffer(),
) {
    init {
        require(publicationIntervalMs >= 0L)
    }

    fun enqueue(messageId: String, fullText: String, chunk: String) {
        if (!buffer.offer(messageId, fullText, chunk)) return
        schedule(publicationIntervalMs, ::flush)
    }

    fun flush() {
        buffer.drain()?.let(publish)
    }

    fun reset() {
        buffer.reset()
    }
}
