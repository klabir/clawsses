package com.clawsses.phone.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUpdateBufferTest {
    @Test
    fun coalescesManyDeltasIntoOnePublication() {
        val buffer = StreamUpdateBuffer()

        assertTrue(buffer.offer("message", "a", "a"))
        assertFalse(buffer.offer("message", "ab", "b"))
        assertFalse(buffer.offer("message", "abc", "c"))

        assertEquals(
            PendingStreamUpdate("message", "abc", "abc"),
            buffer.drain(),
        )
        assertNull(buffer.drain())
    }

    @Test
    fun nextDeltaSchedulesAnotherPublicationAfterDrain() {
        val buffer = StreamUpdateBuffer()
        buffer.offer("message", "first", "first")
        buffer.drain()

        assertTrue(buffer.offer("message", "first second", " second"))
        assertEquals(" second", buffer.drain()?.chunk)
    }

    @Test
    fun changingMessageDropsStalePendingContent() {
        val buffer = StreamUpdateBuffer()
        buffer.offer("old", "stale", "stale")

        assertTrue(buffer.offer("new", "fresh", "fresh"))
        assertEquals(PendingStreamUpdate("new", "fresh", "fresh"), buffer.drain())
    }

    @Test
    fun reconstructsLargeBurstWithoutSchedulingEveryDelta() {
        val buffer = StreamUpdateBuffer()
        val expected = StringBuilder()
        var schedules = 0

        repeat(1_000) { index ->
            val chunk = "chunk-$index "
            expected.append(chunk)
            if (buffer.offer("message", expected.toString(), chunk)) {
                schedules += 1
            }
        }

        assertEquals(1, schedules)
        val update = buffer.drain()
        assertEquals(expected.toString(), update?.fullText)
        assertEquals(expected.toString(), update?.chunk)
    }
}
