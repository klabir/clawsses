package com.clawsses.phone.voice

import com.clawsses.shared.TtsVoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsVoiceCommandsTest {
    @Test
    fun matchesUnambiguousEnglishAndGermanStopPhrases() {
        listOf(
            "stop voice",
            "Stop speaking!",
            "stop reading",
            "Stop the voice output.",
            "Stop the voice output, please.",
            "Please stop playback",
            "stop the TTS output please",
            "TTS stoppen",
            "Sprachausgabe stoppen.",
            "Bitte stoppe die Sprachausgabe",
            "Vorlesen stoppen",
        ).forEach { phrase ->
            assertEquals(TtsVoiceCommands.STOP_CURRENT_OUTPUT, TtsVoiceCommands.match(phrase))
        }
    }

    @Test
    fun doesNotCaptureGenericStopOrChatPrompts() {
        listOf(
            "stop",
            "stop talk mode",
            "stop the current OpenClaw run",
            "why did the voice stop speaking",
        ).forEach { phrase ->
            assertNull(TtsVoiceCommands.match(phrase))
        }
    }
}
