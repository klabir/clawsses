package com.clawsses.phone.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchTranscriptionAudioTest {
    @Test
    fun `five minute mono capture has a fixed bounded size`() {
        val format = BatchAudioFormat()

        assertEquals(9_600_000L, format.maxPcmBytes(5 * 60 * 1_000L))
    }

    @Test
    fun `wav header describes captured pcm bytes`() {
        val format = BatchAudioFormat()
        val dataBytes = 32_000L
        val header = format.wavHeader(dataBytes)
        val littleEndian = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(BatchAudioFormat.WAV_HEADER_BYTES, header.size)
        assertArrayEquals("RIFF".toByteArray(), header.copyOfRange(0, 4))
        assertEquals(36 + dataBytes.toInt(), littleEndian.getInt(4))
        assertArrayEquals("WAVE".toByteArray(), header.copyOfRange(8, 12))
        assertEquals(16_000, littleEndian.getInt(24))
        assertEquals(32_000, littleEndian.getInt(28))
        assertEquals(1, littleEndian.getShort(22).toInt())
        assertEquals(16, littleEndian.getShort(34).toInt())
        assertArrayEquals("data".toByteArray(), header.copyOfRange(36, 40))
        assertEquals(dataBytes.toInt(), littleEndian.getInt(40))
    }

    @Test
    fun `transcription language uses normalized primary subtag`() {
        assertEquals("de", transcriptionLanguage("de-AT"))
        assertEquals("en", transcriptionLanguage(" EN-us "))
        assertEquals(null, transcriptionLanguage(null))
        assertEquals(null, transcriptionLanguage(""))
    }

    @Test
    fun `manual dictation requires both preference and OpenAI availability`() {
        assertEquals(VoiceInputMode.LONG_DICTATION, manualVoiceInputMode(true, true))
        assertEquals(VoiceInputMode.REALTIME, manualVoiceInputMode(true, false))
        assertEquals(VoiceInputMode.REALTIME, manualVoiceInputMode(false, true))
    }
}
