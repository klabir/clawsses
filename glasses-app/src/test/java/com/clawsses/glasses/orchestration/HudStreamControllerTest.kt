package com.clawsses.glasses.orchestration

import com.clawsses.glasses.ui.AgentProgressDisplay
import com.clawsses.glasses.ui.AgentState
import com.clawsses.glasses.ui.ChatHudState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudStreamControllerTest {
    @Test
    fun `first chunk activates streaming and publishes immediately`() {
        val controller = HudStreamController()
        val decision = controller.acceptChunk(
            state = ChatHudState(
                agentProgress = listOf(AgentProgressDisplay("id", "tool", "Working", "active")),
            ),
            messageId = "answer",
            chunk = "Hello",
            publicationPending = false,
        )

        assertEquals(AgentState.STREAMING, decision.state.agentState)
        assertTrue(decision.state.agentProgress.isEmpty())
        assertTrue(decision.publishImmediately)
        assertFalse(decision.schedulePublication)
        assertEquals("Hello", controller.snapshotIfChanged()?.content)
    }

    @Test
    fun `later chunks schedule at most one publication`() {
        val controller = HudStreamController()
        val streaming = ChatHudState(agentState = AgentState.STREAMING)
        controller.acceptChunk(streaming, "answer", "a", false)
        controller.snapshotIfChanged()

        val first = controller.acceptChunk(streaming, "answer", "b", false)
        val second = controller.acceptChunk(streaming, "answer", "c", true)

        assertTrue(first.schedulePublication)
        assertFalse(second.schedulePublication)
        assertEquals("abc", controller.snapshotIfChanged()?.content)
    }

    @Test
    fun `finish and clear reject stale stream work`() {
        val controller = HudStreamController()
        val streaming = ChatHudState(agentState = AgentState.STREAMING)
        controller.acceptChunk(streaming, "answer", "complete", false)

        assertNull(controller.finish("stale"))
        assertEquals("complete", controller.finish("answer")?.content)
        assertNull(controller.snapshotIfChanged())

        controller.acceptChunk(streaming, "next", "discarded", false)
        controller.clear("next")
        assertNull(controller.snapshotIfChanged())
    }
}
