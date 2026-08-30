package com.clawsses.phone.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawStreamPublisherTest {
    @Test
    fun `burst schedules one delayed lossless publication`() {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val published = mutableListOf<PendingStreamUpdate>()
        val publisher = OpenClawStreamPublisher(
            publicationIntervalMs = 64L,
            schedule = { delayMs, publication -> scheduled += delayMs to publication },
            publish = published::add,
        )

        publisher.enqueue("message", "a", "a")
        publisher.enqueue("message", "ab", "b")
        publisher.enqueue("message", "abc", "c")

        assertEquals(1, scheduled.size)
        assertEquals(64L, scheduled.single().first)
        scheduled.single().second()
        assertEquals(listOf(PendingStreamUpdate("message", "abc", "abc")), published)
    }

    @Test
    fun `synchronous terminal flush invalidates delayed duplicate`() {
        val scheduled = mutableListOf<() -> Unit>()
        val published = mutableListOf<PendingStreamUpdate>()
        val publisher = OpenClawStreamPublisher(
            publicationIntervalMs = 64L,
            schedule = { _, publication -> scheduled += publication },
            publish = published::add,
        )

        publisher.enqueue("message", "complete", "complete")
        publisher.flush()
        scheduled.single().invoke()

        assertEquals(1, published.size)
        assertEquals("complete", published.single().fullText)
    }

    @Test
    fun `reset makes an already scheduled publication harmless`() {
        var scheduled: (() -> Unit)? = null
        val published = mutableListOf<PendingStreamUpdate>()
        val publisher = OpenClawStreamPublisher(
            publicationIntervalMs = 64L,
            schedule = { _, publication -> scheduled = publication },
            publish = published::add,
        )

        publisher.enqueue("old", "stale", "stale")
        publisher.reset()
        scheduled?.invoke()

        assertTrue(published.isEmpty())
    }
}
