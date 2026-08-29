package com.clawsses.phone.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectAudioCircuitBreakerTest {
    @Test
    fun disconnectBlocksDirectAudioUntilReconnectHasSettled() {
        val breaker = DirectAudioCircuitBreaker(stableReconnectMs = 5_000L)

        assertTrue(breaker.canStart(1_000L))
        breaker.onDisconnect(duringDirectAttempt = true)
        assertFalse(breaker.canStart(20_000L))

        breaker.onConnected(30_000L)
        assertFalse(breaker.canStart(34_999L))
        assertTrue(breaker.canStart(35_000L))
    }

    @Test
    fun ordinaryDisconnectDoesNotDisableDirectAudio() {
        val breaker = DirectAudioCircuitBreaker()

        breaker.onDisconnect(duringDirectAttempt = false)

        assertTrue(breaker.canStart(0L))
    }

    @Test
    fun emptyDirectResultRequestsPhoneFallback() {
        assertTrue(directCaptureNeedsPhoneFallback(true, "  "))
        assertFalse(directCaptureNeedsPhoneFallback(false, ""))
        assertFalse(directCaptureNeedsPhoneFallback(true, "hello"))
    }
}
