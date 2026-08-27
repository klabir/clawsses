package com.clawsses.glasses.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceActivityLabelTest {
    @Test
    fun `idle has no banner`() {
        assertNull(voiceActivityLabel(VoiceInputState.Idle))
    }

    @Test
    fun `listening states ask the user to speak`() {
        assertEquals("LISTENING - SPEAK NOW", voiceActivityLabel(VoiceInputState.Listening()))
        assertEquals("LISTENING - SPEAK NOW", voiceActivityLabel(VoiceInputState.Recognizing()))
    }

    @Test
    fun `processing and errors are explicit`() {
        assertEquals("PROCESSING...", voiceActivityLabel(VoiceInputState.Processing()))
        assertEquals("VOICE ERROR: mic", voiceActivityLabel(VoiceInputState.Error("mic")))
    }
}
