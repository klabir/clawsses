package com.clawsses.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWakeWordPolicyTest {
    private val idle = LocalWakeWordBlockers(
        foregroundCaptureActive = false,
        talkModeActive = false,
        liveCaptionsActive = false,
        playbackActive = false,
        openClawRunActive = false,
        activationActive = false,
    )

    @Test
    fun `status exposes the exact branded wake phrase`() {
        assertEquals("HEY CLAWSSES", LocalWakeWordStatus().keyword)
    }

    @Test
    fun `opt in and idle audio permit local wake word`() {
        assertTrue(shouldRunLocalWakeWord(enabled = true, faulted = false, blockers = idle))
    }

    @Test
    fun `feature is fail closed while disabled or faulted`() {
        assertFalse(shouldRunLocalWakeWord(enabled = false, faulted = false, blockers = idle))
        assertFalse(shouldRunLocalWakeWord(enabled = true, faulted = true, blockers = idle))
    }

    @Test
    fun `every foreground audio or run owner pauses wake word`() {
        val blocked = listOf(
            idle.copy(foregroundCaptureActive = true),
            idle.copy(talkModeActive = true),
            idle.copy(liveCaptionsActive = true),
            idle.copy(playbackActive = true),
            idle.copy(openClawRunActive = true),
            idle.copy(activationActive = true),
        )

        blocked.forEach { blockers ->
            assertFalse(shouldRunLocalWakeWord(enabled = true, faulted = false, blockers = blockers))
        }
    }
}
