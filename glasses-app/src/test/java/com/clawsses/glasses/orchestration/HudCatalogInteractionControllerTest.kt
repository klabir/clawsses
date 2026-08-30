package com.clawsses.glasses.orchestration

import com.clawsses.glasses.input.GestureHandler.Gesture
import com.clawsses.glasses.input.ModelPageSelection
import com.clawsses.glasses.ui.AgentPickerInfo
import com.clawsses.glasses.ui.ChatHudState
import com.clawsses.glasses.ui.ModelPickerInfo
import com.clawsses.glasses.ui.SessionPickerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudCatalogInteractionControllerTest {
    private val controller = HudCatalogInteractionController(NEW_SESSION, MORE_SESSIONS)

    @Test
    fun `session request opens a bounded loading surface and clamps offset`() {
        val decision = controller.requestSessions(ChatHudState(), offset = -7)

        assertTrue(decision.state.showSessionPicker)
        assertTrue(decision.state.isSessionOperationPending)
        assertEquals(listOf(SessionPickerInfo(NEW_SESSION, "+ New Session")), decision.state.availableSessions)
        assertEquals(listOf(HudCatalogAction.RequestSessions(0)), decision.actions)
        assertEquals(true, decision.sessionRequestActive)
    }

    @Test
    fun `pending new session can create or cancel without selecting stale rows`() {
        val state = ChatHudState(
            showSessionPicker = true,
            availableSessions = listOf(SessionPickerInfo(NEW_SESSION, "+ New Session")),
            isSessionOperationPending = true,
            sessionOperationMessage = "Loading sessions...",
        )

        val create = controller.planSessionGesture(state, Gesture.TAP, nextOffset = null)
        val cancel = controller.planSessionGesture(state, Gesture.DOUBLE_TAP, nextOffset = null)

        assertEquals(listOf(HudCatalogAction.CreateSession), create.actions)
        assertFalse(create.applyStateBeforeActions)
        assertEquals("Creating session...", create.state.sessionOperationMessage)
        assertEquals(false, create.sessionRequestActive)
        assertFalse(cancel.state.showSessionPicker)
        assertFalse(cancel.state.isSessionOperationPending)
        assertNull(cancel.state.sessionOperationMessage)
    }

    @Test
    fun `more sessions requests the advertised next offset`() {
        val state = ChatHudState(
            showSessionPicker = true,
            availableSessions = listOf(
                SessionPickerInfo("session", "Session"),
                SessionPickerInfo(MORE_SESSIONS, "More"),
            ),
            selectedSessionIndex = 1,
        )

        val decision = controller.planSessionGesture(state, Gesture.TAP, nextOffset = 12)

        assertEquals(listOf(HudCatalogAction.RequestSessions(12)), decision.actions)
        assertTrue(decision.state.isSessionOperationPending)
        assertEquals(true, decision.sessionRequestActive)
    }

    @Test
    fun `model page edge requests the next page and preserves forward selection`() {
        val state = modelState(
            availableModels = listOf(model(3, "First"), model(4, "Second")),
            selectedModelIndex = 1,
            modelNextOffset = 5,
        )

        val decision = controller.planModelGesture(state, Gesture.SWIPE_FORWARD)

        assertEquals(
            listOf(HudCatalogAction.RequestModels(5, ModelPageSelection.FIRST)),
            decision.actions,
        )
        assertTrue(decision.state.isModelOperationPending)
        assertEquals(true, decision.modelRequestActive)
    }

    @Test
    fun `model selection rejects unavailable and active run then emits canonical selection`() {
        val unavailable = controller.planModelGesture(
            modelState(availableModels = listOf(model(3, "No", available = false))),
            Gesture.TAP,
        )
        val busy = controller.planModelGesture(
            modelState(availableModels = listOf(model(3, "Busy")), runState = "streaming"),
            Gesture.TAP,
        )
        val selected = controller.planModelGesture(
            modelState(availableModels = listOf(model(3, "Ready"))),
            Gesture.TAP,
        )

        assertEquals("Model is unavailable", unavailable.state.modelOperationError)
        assertEquals("Available after response", busy.state.modelOperationError)
        assertEquals(
            listOf(HudCatalogAction.SelectModel("agent:main:main", "catalog", 3)),
            selected.actions,
        )
        assertEquals("Changing model...", selected.state.modelOperationMessage)
    }

    @Test
    fun `agent selection updates visible identity and emits one switch`() {
        val state = ChatHudState(
            showAgentPicker = true,
            availableAgents = listOf(
                AgentPickerInfo("main", "Main"),
                AgentPickerInfo("vision", "Vision"),
            ),
            selectedAgentIndex = 0,
        )
        val moved = controller.planAgentGesture(state, Gesture.SWIPE_BACKWARD)
        val selected = controller.planAgentGesture(moved.state, Gesture.TAP)

        assertEquals(1, moved.state.selectedAgentIndex)
        assertEquals(listOf(HudCatalogAction.SwitchAgent("vision", "Vision")), selected.actions)
        assertFalse(selected.applyStateBeforeActions)
        assertFalse(selected.state.showAgentPicker)
        assertEquals("vision", selected.state.currentAgentId)
        assertEquals("Vision", selected.state.currentSessionName)
    }

    private fun modelState(
        availableModels: List<ModelPickerInfo>,
        selectedModelIndex: Int = 0,
        modelNextOffset: Int? = null,
        runState: String = "idle",
    ) = ChatHudState(
        showModelPicker = true,
        availableModels = availableModels,
        modelCatalogId = "catalog",
        currentSessionKey = "agent:main:main",
        selectedModelIndex = selectedModelIndex,
        modelNextOffset = modelNextOffset,
        runState = runState,
    )

    private fun model(index: Int, name: String, available: Boolean = true) = ModelPickerInfo(
        index = index,
        name = name,
        provider = "provider",
        available = available,
    )

    private companion object {
        const val NEW_SESSION = "__new_session__"
        const val MORE_SESSIONS = "__more_sessions__"
    }
}
