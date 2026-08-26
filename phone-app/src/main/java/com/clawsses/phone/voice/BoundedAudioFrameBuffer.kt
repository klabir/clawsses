package com.clawsses.phone.voice

import java.util.ArrayDeque

/**
 * Thread-safe rolling audio buffer used while a Realtime session is connecting.
 *
 * The oldest frame is discarded at capacity so a stalled connection cannot grow memory
 * without bound and a recovering session receives the audio closest to the current speech.
 */
internal class BoundedAudioFrameBuffer(private val maxFrames: Int) {
    init {
        require(maxFrames > 0)
    }

    private val frames = ArrayDeque<ByteArray>(maxFrames)

    /** Adds [frame], returning true when the oldest buffered frame had to be discarded. */
    @Synchronized
    fun offer(frame: ByteArray): Boolean {
        val droppedOldest = frames.size == maxFrames
        if (droppedOldest) frames.removeFirst()
        frames.addLast(frame)
        return droppedOldest
    }

    @Synchronized
    fun poll(): ByteArray? = if (frames.isEmpty()) null else frames.removeFirst()

    @Synchronized
    fun clear() {
        frames.clear()
    }

    @Synchronized
    fun size(): Int = frames.size
}
