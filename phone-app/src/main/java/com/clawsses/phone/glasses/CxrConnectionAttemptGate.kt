package com.clawsses.phone.glasses

import java.util.concurrent.atomic.AtomicLong

/** Assigns one generation to every CXR connection attempt and invalidates older callbacks. */
internal class CxrConnectionAttemptGate {
    private val generation = AtomicLong(0L)

    fun begin(): Long = generation.incrementAndGet()
    fun isCurrent(attemptId: Long): Boolean = generation.get() == attemptId
    fun cancel(): Long = generation.incrementAndGet()
}
