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

        assertEquals((0 until 500).joinToString(separator = ",", postfix = ","), accumulator.snapshot()?.content)
        assertEquals(500L, accumulator.snapshot()?.revision)
    }

    @Test
    fun `switching message ids starts a fresh active response`() {
        val accumulator = HudStreamingAccumulator()

        assertTrue(accumulator.append("first", "old"))
        assertFalse(accumulator.append("first", " answer"))
        assertTrue(accumulator.append("second", "new"))

        assertEquals("second", accumulator.snapshot()?.id)
        assertEquals("new", accumulator.snapshot()?.content)
    }

    @Test
    fun `finish returns matching response and clears it`() {
        val accumulator = HudStreamingAccumulator()
        accumulator.append("answer", "complete")

        assertEquals("complete", accumulator.finish("answer")?.content)
        assertNull(accumulator.snapshot())
    }
}
