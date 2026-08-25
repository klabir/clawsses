package com.clawsses.phone.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedByteLruCacheTest {
    @Test
    fun `evicts least recently used entries by byte size`() {
        val cache = BoundedByteLruCache(maxBytes = 6)
        cache.put("a", byteArrayOf(1, 2))
        cache.put("b", byteArrayOf(3, 4))
        assertArrayEquals(byteArrayOf(1, 2), cache.get("a"))

        cache.put("c", byteArrayOf(5, 6, 7))

        assertNull(cache.get("b"))
        assertArrayEquals(byteArrayOf(1, 2), cache.get("a"))
        assertArrayEquals(byteArrayOf(5, 6, 7), cache.get("c"))
        assertEquals(5, cache.sizeBytes())
        assertEquals(2, cache.entryCount())
    }

    @Test
    fun `oversized entry is never retained`() {
        val cache = BoundedByteLruCache(maxBytes = 2)
        cache.put("large", byteArrayOf(1, 2, 3))

        assertNull(cache.get("large"))
        assertEquals(0, cache.sizeBytes())
        assertEquals(0, cache.entryCount())
    }

    @Test
    fun `replacing an entry updates byte accounting`() {
        val cache = BoundedByteLruCache(maxBytes = 8)
        cache.put("same", byteArrayOf(1, 2, 3, 4))
        cache.put("same", byteArrayOf(9))

        assertArrayEquals(byteArrayOf(9), cache.get("same"))
        assertEquals(1, cache.sizeBytes())
        assertEquals(1, cache.entryCount())
    }
}
