package com.clawsses.glasses.state

import com.clawsses.glasses.ui.AgentPickerInfo
import com.clawsses.glasses.ui.AgentProgressDisplay
import com.clawsses.glasses.ui.AgentState
import com.clawsses.glasses.ui.ChatHudState
import com.clawsses.glasses.ui.DisplayMessage
import com.clawsses.glasses.ui.LiveCaptionDisplay
import com.clawsses.glasses.ui.SessionPickerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HudStateReducerTest {
    @Test
    fun `matching optimistic user echo is replaced by id`() {
        val existing = DisplayMessage("local", "user", "hello")
        val state = ChatHudState(messages = listOf(existing))

        val reduced = HudStateReducer.reduce(
            state,
            HudStateEvent.MessageCompleted(DisplayMessage("local", "user", "hello")),
        ).state

        assertEquals(1, reduced.messages.size)
        assertEquals("local", reduced.messages.single().id)
        assertEquals(1, reduced.scrollTrigger)
    }

    @Test
    fun `identical legitimate user messages with different ids are retained`() {
        val state = ChatHudState(messages = listOf(DisplayMessage("first", "user", "yes")))

        val reduced = HudStateReducer.reduce(
            state,
            HudStateEvent.MessageCompleted(DisplayMessage("second", "user", "yes")),
        ).state

        assertEquals(listOf("first", "second"), reduced.messages.map { it.id })
    }

    @Test
    fun `assistant completion replaces matching streaming message`() {
        val state = ChatHudState(
            messages = listOf(DisplayMessage("answer", "assistant", "partial", isStreaming = true)),
            isScrolledToEnd = true,
            scrollTrigger = 4,
        )

        val reduced = HudStateReducer.reduce(
            state,
            HudStateEvent.MessageCompleted(DisplayMessage("answer", "assistant", "complete")),
        ).state

        assertEquals(1, reduced.messages.size)
        assertEquals("complete", reduced.messages.single().content)
        assertFalse(reduced.messages.single().isStreaming)
        assertEquals(5, reduced.scrollTrigger)
    }

    @Test
    fun `history prepend preserves visible message position`() {
        val state = ChatHudState(
            messages = listOf(message("new-1"), message("new-2")),
            scrollPosition = 1,
            isLoadingMoreHistory = true,
        )
        val olderPage = listOf(message("old-1"), message("old-2"))

        val reduction = HudStateReducer.reduce(
            state,
            HudStateEvent.HistoryLoaded(olderPage, isLoadMore = true, hasMore = true),
        )

        assertEquals(2, reduction.prependedCount)
        assertEquals(
            listOf("old-1", "old-2", "new-1", "new-2"),
            reduction.state.messages.map { it.id },
        )
        assertEquals(3, reduction.state.scrollPosition)
        assertEquals(2, reduction.state.newPrependCount)
        assertTrue(reduction.state.hasMoreHistory)
    }

    @Test
    fun `empty final history page marks beginning reached`() {
        val state = ChatHudState(
            messages = listOf(message("only")),
            isLoadingMoreHistory = true,
            hasMoreHistory = true,
        )

        val reduction = HudStateReducer.reduce(
            state,
            HudStateEvent.HistoryLoaded(emptyList(), isLoadMore = true, hasMore = false),
        )

        assertEquals(0, reduction.prependedCount)
        assertFalse(reduction.state.isLoadingMoreHistory)
        assertFalse(reduction.state.hasMoreHistory)
    }

    @Test
    fun `stale load-more page is ignored when no request is pending`() {
        val state = ChatHudState(messages = listOf(message("current")))

        val reduction = HudStateReducer.reduce(
            state,
            HudStateEvent.HistoryLoaded(listOf(message("stale")), isLoadMore = true, hasMore = true),
        )

        assertSame(state, reduction.state)
    }

    @Test
    fun `stream completion appends missing response and clears progress`() {
        val state = ChatHudState(
            agentState = AgentState.STREAMING,
            agentProgress = listOf(AgentProgressDisplay("tool", "tool", "Running", "active")),
        )

        val reduced = HudStateReducer.reduce(
            state,
            HudStateEvent.StreamCompleted("answer", "done"),
        ).state

        assertEquals("done", reduced.messages.single().content)
        assertEquals(AgentState.IDLE, reduced.agentState)
        assertTrue(reduced.agentProgress.isEmpty())
    }

    @Test
    fun `session change closes pending picker and resolves agent identity`() {
        val state = ChatHudState(
            currentSessionKey = "agent:old:main",
            showSessionPicker = true,
            isSessionOperationPending = true,
            sessionOperationMessage = "Loading",
            sessionOperationError = "old",
        )

        val reduced = HudStateReducer.reduce(
            state,
            HudStateEvent.ConnectionChanged(true, "agent:new:main", "New Agent"),
        ).state

        assertTrue(reduced.isConnected)
        assertEquals("new", reduced.currentAgentId)
        assertEquals("New Agent", reduced.currentAgentName)
        assertFalse(reduced.showSessionPicker)
        assertFalse(reduced.isSessionOperationPending)
        assertNull(reduced.sessionOperationMessage)
        assertNull(reduced.sessionOperationError)
    }

    @Test
    fun `session and agent lists select current identities deterministically`() {
        val sessions = listOf(
            SessionPickerInfo("__new_session__", "+ New Session"),
            SessionPickerInfo("agent:main:main", "Main"),
        )
        val withSessions = HudStateReducer.reduce(
            ChatHudState(currentSessionName = "Old"),
            HudStateEvent.SessionsLoaded(sessions, "agent:main:main"),
        ).state
        val agents = listOf(AgentPickerInfo("main", "Main Agent"))
        val withAgents = HudStateReducer.reduce(
            withSessions,
            HudStateEvent.AgentsLoaded(agents, currentAgentId = null, showPicker = true),
        ).state

        assertEquals(1, withSessions.selectedSessionIndex)
        assertEquals("Main", withSessions.currentSessionName)
        assertTrue(withAgents.showAgentPicker)
        assertEquals("main", withAgents.currentAgentId)
        assertEquals("Main Agent", withAgents.currentAgentName)
    }

    @Test
    fun `run talk caption and progress transitions remain independent`() {
        val running = HudStateReducer.reduce(
            ChatHudState(),
            HudStateEvent.RunChanged("reasoning", canAbort = true),
        ).state
        val talking = HudStateReducer.reduce(
            running,
            HudStateEvent.TalkModeChanged(enabled = true, phase = "listening"),
        ).state
        val caption = LiveCaptionDisplay(sourceText = "hello")
        val captioned = HudStateReducer.reduce(
            talking,
            HudStateEvent.LiveCaptionChanged(enabled = true, caption = caption),
        ).state
        val progressed = HudStateReducer.reduce(
            captioned,
            HudStateEvent.AgentProgressChanged("tool", "tool", " Working ", "active"),
        ).state

        assertEquals(AgentState.REASONING, progressed.agentState)
        assertTrue(progressed.runCanAbort)
        assertTrue(progressed.talkModeEnabled)
        assertSame(caption, progressed.liveCaption)
        assertEquals("Working", progressed.agentProgress.single().label)
    }

    @Test
    fun `unknown thinking phase retains legacy thinking fallback`() {
        val reduced = HudStateReducer.reduce(
            ChatHudState(),
            HudStateEvent.AgentPhaseChanged("future-phase"),
        ).state

        assertEquals(AgentState.THINKING, reduced.agentState)
    }

    private fun message(id: String) = DisplayMessage(id, "assistant", id)
}
