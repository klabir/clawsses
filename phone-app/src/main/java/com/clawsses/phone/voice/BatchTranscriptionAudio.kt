package com.clawsses.phone.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class VoiceInputMode {
    REALTIME,
    LONG_DICTATION,
}

internal data class BatchAudioFormat(
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val bitsPerSample: Int = 16,
) {
    init {
        require(sampleRateHz > 0)
        require(channelCount > 0)
        require(bitsPerSample in setOf(8, 16, 24, 32))
    }

    val bytesPerSecond: Int = sampleRateHz * channelCount * (bitsPerSample / 8)

    fun maxPcmBytes(durationMs: Long): Long {
        require(durationMs > 0)
        return bytesPerSecond.toLong() * durationMs / 1_000L
    }

    fun wavHeader(pcmByteCount: Long): ByteArray {
        require(pcmByteCount in 0..Int.MAX_VALUE.toLong())
        val dataSize = pcmByteCount.toInt()
        return ByteBuffer.allocate(WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt(36 + dataSize)
            .put("WAVE".toByteArray(Charsets.US_ASCII))
            .put("fmt ".toByteArray(Charsets.US_ASCII))
            .putInt(16)
            .putShort(1)
            .putShort(channelCount.toShort())
            .putInt(sampleRateHz)
            .putInt(bytesPerSecond)
            .putShort((channelCount * bitsPerSample / 8).toShort())
            .putShort(bitsPerSample.toShort())
            .put("data".toByteArray(Charsets.US_ASCII))
            .putInt(dataSize)
            .array()
    }

    companion object {
        const val WAV_HEADER_BYTES = 44
    }
}

internal fun transcriptionLanguage(languageTag: String?): String? = languageTag
    ?.substringBefore('-')
    ?.trim()
    ?.lowercase()
    ?.takeIf(String::isNotEmpty)

internal fun manualVoiceInputMode(
    longDictationEnabled: Boolean,
    openAiAvailable: Boolean,
): VoiceInputMode = if (longDictationEnabled && openAiAvailable) {
    VoiceInputMode.LONG_DICTATION
} else {
    VoiceInputMode.REALTIME
}
