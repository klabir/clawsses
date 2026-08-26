package com.clawsses.phone.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackManagerTest {
    @Test
    fun shortTextRemainsSingleChunk() {
        assertEquals(listOf("Short response."), TtsPlaybackManager.splitForSynthesis("Short response."))
    }

    @Test
    fun longTextIsSplitWithoutLosingWords() {
        val words = List(1_200) { "word$it" }
        val input = words.joinToString(" ")

        val chunks = TtsPlaybackManager.splitForSynthesis(input)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.first().length <= 400)
        assertTrue(chunks.all { it.length <= 1_500 })
        assertEquals(words, chunks.joinToString(" ").split(" "))
    }

    @Test
    fun mediumTextUsesSmallStartupChunk() {
        val words = List(180) { "word$it" }
        val chunks = TtsPlaybackManager.splitForSynthesis(words.joinToString(" "))

        assertTrue(chunks.size > 1)
        assertTrue(chunks.first().length in 280..400)
        assertTrue(chunks.drop(1).all { it.length <= 1_500 })
        assertEquals(words, chunks.joinToString(" ").split(" "))
    }

    @Test
    fun firstChunkPrefersSentenceBoundaryInStartupWindow() {
        val firstSentence = "a".repeat(319) + "."
        val input = ("$firstSentence ${"next ".repeat(80)}").trim()

        val chunks = TtsPlaybackManager.splitForSynthesis(input)

        assertEquals(firstSentence, chunks.first())
        assertTrue(chunks.first().length in 280..400)
        assertEquals(input, chunks.joinToString(" "))
    }

    @Test
    fun activePlaybackBlocksVoiceCaptureButTerminalStatesDoNot() {
        assertTrue(TtsPlaybackState.SYNTHESIZING.blocksVoiceCapture())
        assertTrue(TtsPlaybackState.PLAYING.blocksVoiceCapture())
        assertTrue(!TtsPlaybackState.IDLE.blocksVoiceCapture())
        assertTrue(!TtsPlaybackState.ERROR.blocksVoiceCapture())
    }
}
