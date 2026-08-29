package com.clawsses.phone.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSessionCoordinatorTest {
    @Test
    fun `playback excludes capture until current lease is released`() {
        val focus = FakeFocusController()
        val coordinator = AudioSessionCoordinator(focus)
        val playback = coordinator.beginPlayback {}

        assertNotNull(playback)
        assertNull(coordinator.beginCapture())
        assertTrue(coordinator.release(requireNotNull(playback)))
        assertNotNull(coordinator.beginCapture())
        assertEquals(1, focus.abandons)
    }

    @Test
    fun `new capture generation invalidates delayed release`() {
        val coordinator = AudioSessionCoordinator(FakeFocusController())
        val first = requireNotNull(coordinator.beginCapture())
        assertTrue(coordinator.release(first))
        val second = requireNotNull(coordinator.beginCapture())

        assertFalse(coordinator.isCurrent(first))
        assertTrue(coordinator.isCurrent(second))
        assertFalse(coordinator.release(first))
        assertTrue(coordinator.isCurrent(second))
    }

    @Test
    fun `playback cannot silently preempt active capture`() {
        val coordinator = AudioSessionCoordinator(FakeFocusController())
        val capture = requireNotNull(coordinator.beginCapture())

        assertNull(coordinator.beginPlayback {})
        assertTrue(coordinator.isCurrent(capture))
    }

    @Test
    fun `focus loss invalidates playback and invokes current callback once`() {
        val focus = FakeFocusController()
        val coordinator = AudioSessionCoordinator(focus)
        var losses = 0
        val playback = requireNotNull(coordinator.beginPlayback { losses += 1 })

        focus.loseFocus()
        focus.loseFocus()

        assertEquals(1, losses)
        assertFalse(coordinator.isCurrent(playback))
        assertFalse(coordinator.release(playback))
    }

    @Test
    fun `denied focus leaves no playback owner`() {
        val focus = FakeFocusController(grant = false)
        val coordinator = AudioSessionCoordinator(focus)

        assertNull(coordinator.beginPlayback {})
        assertNotNull(coordinator.beginCapture())
    }

    private class FakeFocusController(private val grant: Boolean = true) : SpeechAudioFocusController {
        private var loss: (() -> Unit)? = null
        var abandons = 0

        override fun request(onFocusLost: () -> Unit): Boolean {
            loss = onFocusLost
            return grant
        }

        override fun abandon() {
            abandons += 1
            loss = null
        }

        fun loseFocus() = loss?.invoke() ?: Unit
    }
}
