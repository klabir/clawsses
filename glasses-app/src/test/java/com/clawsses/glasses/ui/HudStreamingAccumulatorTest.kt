package com.clawsses.glasses.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudStreamingAccumulatorTest {
    @Test
    fun `combines a large chunk stream without losing content`() {
        val accumulator = HudStreamingAccumulator()

        repeat(500) { index ->
            accumulator.append("answer", "$index,")
        }

        val snapshot = accumulator.snapshotIfChanged()
        assertEquals((0 until 500).joinToString(separator = ",", postfix = ","), snapshot?.content)
        assertEquals(1L, snapshot?.revision)
        assertFalse(accumulator.hasUnpublishedChanges())
        assertNull(accumulator.snapshotIfChanged())
    }

    @Test
    fun `switching message ids starts a fresh active response`() {
        val accumulator = HudStreamingAccumulator()

        assertTrue(accumulator.append("first", "old"))
        assertFalse(accumulator.append("first", " answer"))
        assertTrue(accumulator.append("second", "new"))

        assertEquals("second", accumulator.snapshotIfChanged()?.id)
    }

    @Test
    fun `finish returns matching response and clears it`() {
        val accumulator = HudStreamingAccumulator()
        accumulator.append("answer", "complete")

        assertEquals("complete", accumulator.finish("answer")?.content)
        assertNull(accumulator.snapshotIfChanged())
    }

    @Test
    fun `later chunks create only one additional published revision`() {
        val accumulator = HudStreamingAccumulator()
        accumulator.append("answer", "Hello")
        assertEquals(1L, accumulator.snapshotIfChanged()?.revision)

        accumulator.append("answer", " ")
        accumulator.append("answer", "world")

        val snapshot = accumulator.snapshotIfChanged()
        assertEquals("Hello world", snapshot?.content)
        assertEquals(2L, snapshot?.revision)
    }

    @Test
    fun `empty chunks and blank ids do not mutate the stream`() {
        val accumulator = HudStreamingAccumulator()

        assertFalse(accumulator.append("", "ignored"))
        assertFalse(accumulator.append("answer", ""))
        assertNull(accumulator.snapshotIfChanged())
    }
}
