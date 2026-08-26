package com.clawsses.phone.tts

import android.media.AudioDeviceInfo
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlaybackManagerTest {
    @Test
    fun pcmPlaybackUsesRokidScoWhenBothBluetoothRoutesExist() {
        assertEquals(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            TtsPlaybackManager.selectPcmOutputType(
                listOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                )
            ),
        )
    }

    @Test
    fun pcmPlaybackDoesNotFallBackToA2dpForGlassesSpeech() {
        assertEquals(null, TtsPlaybackManager.selectPcmOutputType(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
    }

    @Test
    fun pcmWriterToleratesTemporaryZeroProgress() {
        val results = ArrayDeque(listOf(0, 0, 3, 2))
        var waits = 0

        val written = TtsPlaybackManager.writeFully(
            byteCount = 5,
            write = { _, _ -> results.removeFirst() },
            onNoProgress = { waits += 1 },
        )

        assertEquals(5, written)
        assertEquals(2, waits)
    }

    @Test
    fun pcmFrameAssemblerPreservesFramesAcrossOddNetworkChunks() {
        val output = mutableListOf<Byte>()
        val assembler = PcmFrameAssembler { bytes, offset, byteCount ->
            output += bytes.copyOfRange(offset, offset + byteCount).toList()
            byteCount
        }

        assembler.write(byteArrayOf(1, 2, 3), 3)
        assembler.write(byteArrayOf(4), 1)
        assembler.write(byteArrayOf(5, 6), 2)
        assembler.finish()

        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), output)
    }

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
