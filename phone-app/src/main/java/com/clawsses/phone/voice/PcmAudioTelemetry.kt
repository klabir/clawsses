package com.clawsses.phone.voice

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

internal data class PcmAudioTelemetrySnapshot(
    val totalBytes: Long,
    val maxPeak: Int,
)

/** Privacy-safe PCM16 diagnostics. Tracks only byte count and maximum absolute amplitude. */
internal class PcmAudioTelemetry {
    private val totalBytes = AtomicLong(0L)
    private val maxPeak = AtomicInteger(0)

    fun reset() {
        totalBytes.set(0L)
        maxPeak.set(0)
    }

    fun recordPcm16(data: ByteArray) {
        totalBytes.addAndGet(data.size.toLong())
        var chunkPeak = 0
        var index = 0
        while (index + 1 < data.size) {
            val sample = (((data[index + 1].toInt() shl 8) or
                (data[index].toInt() and 0xff)).toShort().toInt())
            val magnitude = if (sample == Short.MIN_VALUE.toInt()) {
                Short.MAX_VALUE.toInt()
            } else {
                abs(sample)
            }
            if (magnitude > chunkPeak) chunkPeak = magnitude
            index += 2
        }
        maxPeak.updateAndGet { previous -> maxOf(previous, chunkPeak) }
    }

    fun snapshot(): PcmAudioTelemetrySnapshot = PcmAudioTelemetrySnapshot(
        totalBytes = totalBytes.get(),
        maxPeak = maxPeak.get(),
    )
}
