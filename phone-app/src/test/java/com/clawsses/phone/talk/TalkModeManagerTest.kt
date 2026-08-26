package com.clawsses.phone.talk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkModeManagerTest {
    @Test
    fun talkModeDefaultsToEnabledFollowUp() {
        assertTrue(TalkModeManager.DEFAULT_ENABLED)
        assertEquals(TalkInteractionMode.FOLLOW_UP, TalkModeManager.DEFAULT_INTERACTION_MODE)
        assertEquals(12_000L, TalkModeManager.FOLLOW_UP_WINDOW_MS)
    }

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
    fun speakingInvalidatesTheListeningCycleOnce() {
        val listening = TalkModeTransitions.beginListening(
            TalkModeTransitions.setEnabled(TalkModeState(), true, TalkModeSource.GLASSES),
            TalkModeSource.GLASSES,
        )

        val speaking = TalkModeTransitions.beginSpeaking(listening)
        val repeated = TalkModeTransitions.beginSpeaking(speaking)

        assertEquals(TalkModePhase.SPEAKING, speaking.phase)
        assertEquals(listening.cycleId + 1L, speaking.cycleId)
        assertEquals(speaking, repeated)
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
            TalkModeTransitions.setEnabled(
                TalkModeState(interactionMode = TalkInteractionMode.ALWAYS_LISTENING),
                true,
                TalkModeSource.GLASSES,
            ),
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

    @Test
    fun followUpModeRequiresExplicitActivation() {
        val armed = TalkModeTransitions.setInteractionMode(
            TalkModeTransitions.setEnabled(TalkModeState(), true, TalkModeSource.GLASSES),
            TalkInteractionMode.FOLLOW_UP,
        )

        val automatic = TalkModeTransitions.beginListening(
            armed,
            TalkModeSource.GLASSES,
            TalkActivation.AUTOMATIC,
        )
        val explicit = TalkModeTransitions.beginListening(
            armed,
            TalkModeSource.GLASSES,
            TalkActivation.EXPLICIT,
        )

        assertEquals(TalkModePhase.IDLE, automatic.phase)
        assertFalse(automatic.conversationActive)
        assertEquals(TalkModePhase.LISTENING, explicit.phase)
        assertTrue(explicit.conversationActive)
    }

    @Test
    fun followUpModeContinuesOnlyAfterACompletedResponse() {
        val active = TalkModeTransitions.beginListening(
            TalkModeTransitions.setInteractionMode(
                TalkModeTransitions.setEnabled(TalkModeState(), true, TalkModeSource.GLASSES),
                TalkInteractionMode.FOLLOW_UP,
            ),
            TalkModeSource.GLASSES,
            TalkActivation.EXPLICIT,
        )

        assertTrue(TalkModeTransitions.shouldRestart(active, TalkRestartReason.RESPONSE_COMPLETE))
        assertFalse(TalkModeTransitions.shouldRestart(active, TalkRestartReason.EMPTY_RESULT))
        assertFalse(TalkModeTransitions.shouldRestart(active, TalkRestartReason.RECOGNITION_ERROR))
    }

    @Test
    fun alwaysListeningRestartsForEveryRecoverableReason() {
        val enabled = TalkModeTransitions.setEnabled(
            TalkModeState(interactionMode = TalkInteractionMode.ALWAYS_LISTENING),
            true,
            TalkModeSource.GLASSES,
        )

        TalkRestartReason.entries.forEach { reason ->
            assertTrue("Expected restart for $reason", TalkModeTransitions.shouldRestart(enabled, reason))
        }
    }

    @Test
    fun followUpConversationClosesOnStandby() {
        val listening = TalkModeTransitions.beginListening(
            TalkModeTransitions.setInteractionMode(
                TalkModeTransitions.setEnabled(TalkModeState(), true, TalkModeSource.GLASSES),
                TalkInteractionMode.FOLLOW_UP,
            ),
            TalkModeSource.GLASSES,
            TalkActivation.EXPLICIT,
        )

        val standby = TalkModeTransitions.pauseForStandby(listening)

        assertFalse(standby.conversationActive)
        assertFalse(TalkModeTransitions.shouldResumeFromStandby(standby, glassesAwake = true))
    }
}
