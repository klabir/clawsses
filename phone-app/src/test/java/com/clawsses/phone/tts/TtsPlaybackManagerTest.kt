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
        assertTrue(chunks.all { it.length <= 3_500 })
        assertEquals(words, chunks.joinToString(" ").split(" "))
    }
}
