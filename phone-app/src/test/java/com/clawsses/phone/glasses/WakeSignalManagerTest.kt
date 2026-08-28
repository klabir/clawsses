package com.clawsses.phone.glasses

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeSignalManagerTest {
    @Test
    fun `unknown state queues wake control before regular payload and closes gate`() {
        val harness = Harness()

        val sentImmediately = harness.manager.sendMessage(
            json = """{"type":"chat_message"}""",
            isNewMessage = true,
        )

        assertFalse(sentImmediately)
        assertEquals(listOf(false), harness.gates)
        assertEquals(listOf(true, false), harness.enqueued.map { it.second })
        assertEquals("wake_signal", typeOf(harness.enqueued[0].first))
        assertEquals("chat_message", typeOf(harness.enqueued[1].first))
        harness.manager.cleanup()
    }

    @Test
    fun `additional payload while waking uses same queue without duplicate wake`() {
        val harness = Harness()
        harness.manager.sendMessage("""{"type":"chat_message","id":"one"}""")

        harness.manager.sendMessage("""{"type":"chat_message","id":"two"}""")

        assertEquals(3, harness.enqueued.size)
        assertEquals(1, harness.enqueued.count { typeOf(it.first) == "wake_signal" })
        assertEquals(listOf(true, false, false), harness.enqueued.map { it.second })
        harness.manager.cleanup()
    }

    @Test
    fun `glasses activity opens delivery gate without replay buffer`() {
        val harness = Harness()
        harness.manager.sendMessage("""{"type":"chat_message"}""")

        harness.manager.handleGlassesActivity()

        assertTrue(harness.manager.wakeState.value is WakeSignalManager.WakeState.Awake)
        assertEquals(listOf(false, true), harness.gates)
        assertEquals(2, harness.enqueued.size)
        harness.manager.cleanup()
    }

    @Test
    fun `disconnect closes and reconnect opens delivery gate`() {
        val harness = Harness()

        harness.manager.handleGlassesConnected()
        harness.manager.handleGlassesDisconnected()

        assertEquals(listOf(true, false), harness.gates)
        assertTrue(harness.manager.wakeState.value is WakeSignalManager.WakeState.Unknown)
        harness.manager.cleanup()
    }

    @Test
    fun `negative wake acknowledgement resets state so retry is not suppressed`() {
        val harness = Harness()
        harness.manager.sendMessage("""{"type":"chat_message"}""")

        harness.manager.handleWakeAck(ready = false)

        assertTrue(harness.manager.wakeState.value is WakeSignalManager.WakeState.Unknown)
        assertEquals(false, harness.gates.last())
        harness.manager.cleanup()
    }

    @Test
    fun `disabling wake feature cancels pending standby transition`() = runBlocking {
        val gates = mutableListOf<Boolean>()
        val manager = WakeSignalManager(
            enqueueToGlasses = { _, _ -> },
            setDeliveryAllowed = { gates += it },
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            logger = { _, _ -> },
            monotonicClock = { 42L },
            standbyDetectionMs = 20L,
        )

        manager.handleGlassesConnected()
        manager.setEnabled(false)
        delay(60L)

        assertTrue(manager.wakeState.value is WakeSignalManager.WakeState.Awake)
        assertTrue(gates.last())
        manager.cleanup()
    }

    private class Harness {
        val enqueued = mutableListOf<Pair<String, Boolean>>()
        val gates = mutableListOf<Boolean>()
        val manager = WakeSignalManager(
            enqueueToGlasses = { payload, bypass -> enqueued += payload to bypass },
            setDeliveryAllowed = { gates += it },
            pendingMessageCount = { enqueued.count { !it.second } },
            wakeHardwareDisplay = { true },
            scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
            logger = { _, _ -> },
            monotonicClock = { 10_000L },
        )
    }

    private fun typeOf(payload: String): String =
        JsonParser.parseString(payload).asJsonObject.get("type").asString
}
