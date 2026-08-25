package com.clawsses.phone.glasses

import com.clawsses.phone.glasses.HudForegroundRecovery.ForegroundAction.CANCEL_RECOVERY
import com.clawsses.phone.glasses.HudForegroundRecovery.ForegroundAction.NONE
import com.clawsses.phone.glasses.HudForegroundRecovery.ForegroundAction.SCHEDULE_RECOVERY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudForegroundRecoveryTest {
    private val launcher = "com.rokid.os.sprite.launcher"
    private val hud = "com.clawsses.glasses"
    private val recovery = HudForegroundRecovery(
        launcherPackage = launcher,
        hudPackage = hud,
        recoveryWindowMs = 5_000L,
    )

    @Test
    fun `unarmed launcher belongs to user`() {
        assertEquals(NONE, recovery.onForegroundChanged(launcher, true, 10_000L))
        assertFalse(recovery.consumeScheduledRecovery(true, 10_001L))
    }

    @Test
    fun `armed launcher schedules exactly one recovery`() {
        recovery.armForAiExit(10_000L)

        assertEquals(SCHEDULE_RECOVERY, recovery.onForegroundChanged(launcher, true, 10_100L))
        assertEquals(NONE, recovery.onForegroundChanged(launcher, true, 10_200L))
        assertTrue(recovery.consumeScheduledRecovery(true, 10_300L))
        assertFalse(recovery.consumeScheduledRecovery(true, 10_400L))
    }

    @Test
    fun `duplicate exit signal does not cancel an already scheduled recovery`() {
        recovery.armForAiExit(10_000L)
        assertEquals(SCHEDULE_RECOVERY, recovery.onForegroundChanged(launcher, true, 10_100L))

        recovery.armForAiExit(10_200L)
        assertTrue(recovery.consumeScheduledRecovery(true, 10_300L))
    }

    @Test
    fun `foreign app cancels a scheduled recovery`() {
        recovery.armForAiExit(10_000L)
        assertEquals(SCHEDULE_RECOVERY, recovery.onForegroundChanged(launcher, true, 10_100L))

        assertEquals(CANCEL_RECOVERY, recovery.onForegroundChanged("com.rokid.gmaps", true, 10_200L))
        assertFalse(recovery.consumeScheduledRecovery(true, 10_300L))
    }

    @Test
    fun `hud resume cancels a scheduled recovery`() {
        recovery.armForAiExit(10_000L)
        assertEquals(SCHEDULE_RECOVERY, recovery.onForegroundChanged(launcher, true, 10_100L))

        assertEquals(CANCEL_RECOVERY, recovery.onForegroundChanged(hud, true, 10_200L))
        assertFalse(recovery.consumeScheduledRecovery(true, 10_300L))
    }

    @Test
    fun `expired recovery window does not reclaim launcher`() {
        recovery.armForAiExit(10_000L)

        assertEquals(NONE, recovery.onForegroundChanged(launcher, true, 15_001L))
        assertFalse(recovery.consumeScheduledRecovery(true, 15_002L))
    }

    @Test
    fun `disconnect cancels recovery`() {
        recovery.armForAiExit(10_000L)
        assertEquals(SCHEDULE_RECOVERY, recovery.onForegroundChanged(launcher, true, 10_100L))

        assertEquals(CANCEL_RECOVERY, recovery.onForegroundChanged(launcher, false, 10_200L))
        assertFalse(recovery.consumeScheduledRecovery(true, 10_300L))
    }

    @Test
    fun `reset cancels recovery`() {
        recovery.armForAiExit(10_000L)
        assertEquals(SCHEDULE_RECOVERY, recovery.onForegroundChanged(launcher, true, 10_100L))

        recovery.reset()
        assertFalse(recovery.consumeScheduledRecovery(true, 10_200L))
    }
}
