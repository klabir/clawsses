package com.clawsses.glasses.orchestration

import com.clawsses.glasses.input.ModelPageSelection
import com.clawsses.glasses.protocol.PhoneHudMessage
import com.clawsses.glasses.ui.ChatHudState
import com.clawsses.glasses.ui.SessionPickerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HudPhoneMessageEffectPlannerTest {
    private val planner = HudPhoneMessageEffectPlanner("new", "more")
    private val context = HudPhoneMessageEffectContext(
        sessionPickerRequested = true,
        modelPickerRequested = true,
        agentPickerRequested = true,
        pendingModelPageSelection = ModelPageSelection.CURRENT,
    )

    @Test
    fun `session page adds bounded navigation sentinels and completes request`() {
        val effect = planner.plan(
            ChatHudState(),
            PhoneHudMessage.SessionList(
                sessions = listOf(PhoneHudMessage.Session("agent:main:x", "Home", null, true, 42L)),
                currentSessionKey = "agent:main:x",
                nextOffset = 3,
            ),
            context,
        ) as HudPhoneMessageEffect.Apply

        assertEquals(listOf("new", "agent:main:x", "more"), effect.state.availableSessions.map { it.key })
        assertEquals(3, effect.sessionNextOffset)
        assertTrue(effect.sessionOffsetChanged)
        assertTrue(effect.sessionRequestCompleted)
    }

    @Test
    fun `session error restores new-session action and preserves picker`() {
        val effect = planner.plan(
            ChatHudState(availableSessions = listOf(SessionPickerInfo("existing", "Existing"))),
            PhoneHudMessage.SessionOperation("create", "error", "Denied"),
            context,
        ) as HudPhoneMessageEffect.Apply

        assertTrue(effect.state.showSessionPicker)
        assertEquals("new", effect.state.availableSessions.first().key)
        assertEquals("Denied", effect.state.sessionOperationError)
        assertTrue(effect.sessionRequestCompleted)
    }

    @Test
    fun `model page keeps requested picker visible and selects current model`() {
        val effect = planner.plan(
            ChatHudState(),
            PhoneHudMessage.ModelPage(
                models = listOf(
                    PhoneHudMessage.Model(2, "Two", "p", true),
                    PhoneHudMessage.Model(3, "Three", "p", true),
                ),
                catalogId = "catalog",
                currentIndex = 3,
                offset = 2,
                nextOffset = 4,
                pageIndex = 1,
                pageCount = 2,
                error = null,
            ),
            context,
        ) as HudPhoneMessageEffect.Apply

        assertTrue(effect.state.showModelPicker)
        assertEquals(1, effect.state.selectedModelIndex)
        assertTrue(effect.modelRequestCompleted)
        assertTrue(effect.resetModelPageSelection)
    }

    @Test
    fun `valid HUD card replaces matching id and requests expiry scheduling`() {
        val first = planner.plan(
            ChatHudState(),
            PhoneHudMessage.HudCard("id", "source", "Old", "Body", "normal", null, emptyList()),
            context,
        ) as HudPhoneMessageEffect.Apply
        val second = planner.plan(
            first.state,
            PhoneHudMessage.HudCard("id", "source", "New", "Body", "high", 99L, emptyList()),
            context,
        ) as HudPhoneMessageEffect.Apply

        assertEquals(1, second.state.hudCards.size)
        assertEquals("New", second.state.hudCards.single().title)
        assertTrue(second.scheduleCardExpiry)
    }

    @Test
    fun `runtime-owned messages are left to activity hardware handlers`() {
        val state = ChatHudState()
        val effect = planner.plan(state, PhoneHudMessage.WakeSignal("wake", 1), context)

        assertSame(HudPhoneMessageEffect.RuntimeOwned, effect)
        val invalidCard = planner.plan(
            state,
            PhoneHudMessage.HudCard("", "source", "Title", "", "normal", null, emptyList()),
            context,
        ) as HudPhoneMessageEffect.Apply
        assertEquals(state, invalidCard.state)
        assertFalse(invalidCard.scheduleCardExpiry)
    }
}
