package com.clawsses.glasses.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudHistorySnapshotAssemblerTest {
    @Test
    fun `matching chunks assemble atomically in message order`() {
        val assembler = HudHistorySnapshotAssembler()
        assembler.begin("new", isLoadMore = true, hasMore = true)

        assertTrue(assembler.append("new", "one", "assistant", "first "))
        assertTrue(assembler.append("new", "one", "assistant", "message"))
        assertTrue(assembler.append("new", "two", "user", "second"))
        val snapshot = requireNotNull(assembler.finish("new"))

        assertEquals(listOf("one", "two"), snapshot.messages.map { it.id })
        assertEquals("first message", snapshot.messages.first().content)
        assertTrue(snapshot.isLoadMore)
        assertTrue(snapshot.hasMore)
        assertNull(assembler.finish("new"))
    }

    @Test
    fun `new begin invalidates delayed chunks and end marker`() {
        val assembler = HudHistorySnapshotAssembler()
        assembler.begin("old", isLoadMore = false, hasMore = false)
        assembler.append("old", "stale", "assistant", "stale")

        assembler.begin("new", isLoadMore = false, hasMore = true)

        assertFalse(assembler.append("old", "stale", "assistant", " late"))
        assertNull(assembler.finish("old"))
        assertTrue(assembler.append("new", "fresh", "assistant", "fresh"))
        assertEquals("fresh", assembler.finish("new")?.messages?.single()?.content)
    }
}
