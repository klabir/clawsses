package com.clawsses.phone.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudForegroundRecoveryTest {
    private val recovery = HudForegroundRecovery(
        launcherPackage = "com.rokid.os.sprite.launcher",
        cooldownMs = 3_000L,
    )

    @Test
    fun `recovers connected launcher takeover`() {
        assertTrue(
            recovery.shouldRecover(
                packageName = "com.rokid.os.sprite.launcher",
                connected = true,
                nowMs = 10_000L,
            )
        )
    }

    @Test
    fun `ignores duplicate launcher callbacks inside cooldown`() {
        assertTrue(recovery.shouldRecover("com.rokid.os.sprite.launcher", true, 10_000L))
        assertFalse(recovery.shouldRecover("com.rokid.os.sprite.launcher", true, 11_400L))
        assertTrue(recovery.shouldRecover("com.rokid.os.sprite.launcher", true, 13_000L))
    }

    @Test
    fun `ignores launcher when disconnected and ignores other packages`() {
        assertFalse(recovery.shouldRecover("com.rokid.os.sprite.launcher", false, 10_000L))
        assertFalse(recovery.shouldRecover("com.clawsses.glasses", true, 10_000L))
    }

    @Test
    fun `reset permits immediate recovery after reconnect`() {
        assertTrue(recovery.shouldRecover("com.rokid.os.sprite.launcher", true, 10_000L))
        recovery.reset()
        assertTrue(recovery.shouldRecover("com.rokid.os.sprite.launcher", true, 10_100L))
    }
}
