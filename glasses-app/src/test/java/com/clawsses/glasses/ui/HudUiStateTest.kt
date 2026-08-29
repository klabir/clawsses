package com.clawsses.glasses.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HudUiStateTest {
    @Test
    fun statusChangeKeepsChatInputAndPickerSlicesStable() {
        val original = ChatHudState(
            messages = listOf(DisplayMessage("m1", "assistant", "hello")),
            currentSessionKey = "session-1",
        ).toHudUiState()
        val changed = ChatHudState(
            messages = listOf(DisplayMessage("m1", "assistant", "hello")),
            currentSessionKey = "session-1",
            ttsEnabled = true,
        ).toHudUiState()

        assertEquals(original.chat, changed.chat)
        assertEquals(original.input, changed.input)
        assertEquals(original.pickers, changed.pickers)
        assertNotEquals(original.status, changed.status)
    }

    @Test
    fun chatChangeDoesNotInvalidateUnrelatedSlices() {
        val original = ChatHudState().toHudUiState()
        val changed = ChatHudState(
            messages = listOf(DisplayMessage("m1", "user", "hello")),
        ).toHudUiState()

        assertNotEquals(original.chat, changed.chat)
        assertEquals(original.input, changed.input)
        assertEquals(original.pickers, changed.pickers)
        assertEquals(original.status, changed.status)
    }
}
