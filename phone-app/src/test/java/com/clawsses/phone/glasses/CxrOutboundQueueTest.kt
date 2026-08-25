package com.clawsses.phone.glasses

import com.clawsses.shared.CxrPayloadLimits
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CxrOutboundQueueTest {
    @Test
    fun `critical completion preserves dependent stream order`() {
        val queue = CxrOutboundQueue()
        queue.enqueue(stream("message", "first"))
        queue.enqueue(message("chat_stream_end", CxrPriority.CRITICAL, reliable = true))

        assertEquals("chat_stream", queue.poll()?.type)
        assertEquals("chat_stream_end", queue.poll()?.type)
    }

    @Test
    fun `pending chat chunks coalesce without losing text`() {
        val queue = CxrOutboundQueue()
        queue.enqueue(stream("message", "hello "))
        val result = queue.enqueue(stream("message", "world"))

        assertTrue(result.coalesced)
        val queued = queue.poll()
        assertNotNull(queued)
        val json = JsonParser.parseString(queued!!.payload).asJsonObject
        assertEquals("hello world", json.get("chunk").asString)
        assertTrue(CxrPayloadLimits.fits(queued.payload))
    }

    @Test
    fun `oversized combined chunks stay as separate messages`() {
        val queue = CxrOutboundQueue()
        queue.enqueue(stream("message", "a".repeat(300)))
        val result = queue.enqueue(stream("message", "b".repeat(300)))

        assertTrue(!result.coalesced)
        assertEquals(2, queue.size)
    }

    @Test
    fun `latest transient state replaces older state`() {
        val queue = CxrOutboundQueue()
        queue.enqueue(progress("tool", "Searching"))
        val result = queue.enqueue(progress("tool", "Reading"))

        assertTrue(result.coalesced)
        val json = JsonParser.parseString(queue.poll()!!.payload).asJsonObject
        assertEquals("Reading", json.get("label").asString)
    }

    @Test
    fun `bounded queue rejects excess ordered critical messages`() {
        val queue = CxrOutboundQueue(maxSize = 2)
        queue.enqueue(message("first", CxrPriority.CRITICAL, reliable = true))
        queue.enqueue(message("second", CxrPriority.CRITICAL, reliable = true))
        val result = queue.enqueue(message("third", CxrPriority.CRITICAL, reliable = true))

        assertTrue(!result.accepted)
        assertEquals(1, result.dropped)
        assertEquals(2, queue.size)
        assertEquals(listOf("first", "second"), generateSequence { queue.poll()?.type }.toList())
    }

    @Test
    fun `critical saturation never exceeds hard capacity`() {
        val queue = CxrOutboundQueue(maxSize = 128)

        repeat(10_000) { index ->
            queue.enqueue(message("critical-$index", CxrPriority.CRITICAL, reliable = true))
            assertTrue(queue.size <= 128)
        }

        assertEquals(128, queue.size)
    }

    @Test
    fun `new ordered critical message evicts supersedable critical state`() {
        val queue = CxrOutboundQueue(maxSize = 2)
        queue.enqueue(message("history", CxrPriority.CRITICAL, reliable = true))
        queue.enqueue(message("run_state", CxrPriority.CRITICAL, reliable = true, coalesceKey = "run_state"))

        val result = queue.enqueue(message("history_end", CxrPriority.CRITICAL, reliable = true))

        assertTrue(result.accepted)
        assertEquals(1, result.dropped)
        assertEquals(listOf("history", "history_end"), generateSequence { queue.poll()?.type }.toList())
    }

    @Test
    fun `latest critical state replaces older state without growing queue`() {
        val queue = CxrOutboundQueue(maxSize = 2)
        queue.enqueue(message("run_state", CxrPriority.CRITICAL, reliable = true, coalesceKey = "run_state"))

        val result = queue.enqueue(message("run_state", CxrPriority.CRITICAL, reliable = true, coalesceKey = "run_state"))

        assertTrue(result.accepted)
        assertTrue(result.coalesced)
        assertEquals(1, queue.size)
    }

    @Test
    fun `atomic history snapshot packets require acknowledgements`() {
        assertTrue("chat_history_begin" in RELIABLE_CXR_TYPES)
        assertTrue("chat_history_chunk" in RELIABLE_CXR_TYPES)
        assertTrue("chat_history_end" in RELIABLE_CXR_TYPES)
    }

    private fun stream(id: String, chunk: String) = CxrQueuedMessage(
        payload = """{"type":"chat_stream","id":"$id","chunk":"$chunk"}""",
        type = "chat_stream",
        priority = CxrPriority.TRANSIENT,
        coalesceKey = "chat_stream:$id",
        reliable = false,
    )

    private fun progress(id: String, label: String) = CxrQueuedMessage(
        payload = """{"type":"agent_progress","id":"$id","label":"$label"}""",
        type = "agent_progress",
        priority = CxrPriority.TRANSIENT,
        coalesceKey = "agent_progress:$id",
        reliable = false,
    )

    private fun message(
        type: String,
        priority: CxrPriority,
        reliable: Boolean,
        coalesceKey: String? = null,
    ) = CxrQueuedMessage(
        payload = """{"type":"$type"}""",
        type = type,
        priority = priority,
        coalesceKey = coalesceKey,
        reliable = reliable,
    )
}
