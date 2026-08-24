package com.clawsses.phone.talk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkModeManagerTest {
    @Test
    fun enablingAndListeningCreatesNewCycle() {
        val enabled = TalkModeTransitions.setEnabled(
            TalkModeState(),
            enabled = true,
            source = TalkModeSource.GLASSES,
        )
        val listening = TalkModeTransitions.beginListening(enabled, TalkModeSource.GLASSES)

        assertTrue(listening.enabled)
        assertEquals(TalkModePhase.LISTENING, listening.phase)
        assertEquals(1L, listening.cycleId)
    }

    @Test
    fun staleRecognitionResultCannotAdvanceNewCycle() {
        val first = TalkModeTransitions.beginListening(
            TalkModeTransitions.setEnabled(TalkModeState(), true, TalkModeSource.GLASSES),
            TalkModeSource.GLASSES,
        )
        val second = TalkModeTransitions.beginListening(first, TalkModeSource.GLASSES)

        val stale = TalkModeTransitions.setPhase(
            second,
            TalkModePhase.SENDING,
            cycleId = first.cycleId,
        )

        assertEquals(second, stale)
    }

    @Test
    fun disablingAlwaysLeavesNonInterruptibleOffState() {
        val speaking = TalkModeState(
            enabled = true,
            phase = TalkModePhase.SPEAKING,
            source = TalkModeSource.PHONE,
        )
        assertTrue(speaking.interruptible)

        val disabled = TalkModeTransitions.setEnabled(
            speaking,
            enabled = false,
            source = TalkModeSource.PHONE,
        )

        assertFalse(disabled.enabled)
        assertFalse(disabled.interruptible)
        assertEquals(TalkModePhase.OFF, disabled.phase)
    }

    @Test
    fun standbyPausesGlassesCaptureAndInvalidatesItsCycle() {
        val listening = TalkModeTransitions.beginListening(
            TalkModeTransitions.setEnabled(TalkModeState(), true, TalkModeSource.GLASSES),
            TalkModeSource.GLASSES,
        )

        assertTrue(TalkModeTransitions.shouldPauseForStandby(listening, glassesAwake = false))
        val standby = TalkModeTransitions.pauseForStandby(listening)

        assertEquals(TalkModePhase.STANDBY, standby.phase)
        assertEquals(listening.cycleId + 1L, standby.cycleId)
        assertTrue(TalkModeTransitions.shouldResumeFromStandby(standby, glassesAwake = true))
    }

    @Test
    fun standbyDoesNotInterruptAnAnswerAlreadyInProgress() {
        val waiting = TalkModeState(
            enabled = true,
            phase = TalkModePhase.WAITING,
            source = TalkModeSource.GLASSES,
        )

        assertFalse(TalkModeTransitions.shouldPauseForStandby(waiting, glassesAwake = false))
    }

    @Test
    fun phoneTalkModeIsIndependentOfGlassesStandby() {
        val listening = TalkModeState(
            enabled = true,
            phase = TalkModePhase.LISTENING,
            source = TalkModeSource.PHONE,
        )

        assertFalse(TalkModeTransitions.shouldPauseForStandby(listening, glassesAwake = false))
    }
}
