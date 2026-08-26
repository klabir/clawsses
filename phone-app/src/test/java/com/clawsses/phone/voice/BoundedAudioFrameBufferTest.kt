package com.clawsses.phone.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedAudioFrameBufferTest {
    @Test
    fun `retains frames in FIFO order below capacity`() {
        val buffer = BoundedAudioFrameBuffer(maxFrames = 3)

        assertFalse(buffer.offer(byteArrayOf(1)))
        assertFalse(buffer.offer(byteArrayOf(2)))

        assertArrayEquals(byteArrayOf(1), buffer.poll())
        assertArrayEquals(byteArrayOf(2), buffer.poll())
        assertNull(buffer.poll())
    }

    @Test
    fun `drops oldest frame when capacity is reached`() {
        val buffer = BoundedAudioFrameBuffer(maxFrames = 2)

        assertFalse(buffer.offer(byteArrayOf(1)))
        assertFalse(buffer.offer(byteArrayOf(2)))
        assertTrue(buffer.offer(byteArrayOf(3)))

        assertEquals(2, buffer.size())
        assertArrayEquals(byteArrayOf(2), buffer.poll())
        assertArrayEquals(byteArrayOf(3), buffer.poll())
    }

    @Test
    fun `clear removes all buffered frames`() {
        val buffer = BoundedAudioFrameBuffer(maxFrames = 2)
        buffer.offer(byteArrayOf(1))

        buffer.clear()

        assertEquals(0, buffer.size())
        assertNull(buffer.poll())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `requires positive capacity`() {
        BoundedAudioFrameBuffer(maxFrames = 0)
    }
}
