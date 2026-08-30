package com.clawsses.glasses.orchestration

import com.clawsses.glasses.protocol.PhoneHudMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudPhoneMessageControllerTest {
    @Test
    fun `typed transaction is delivered before one acknowledgment`() {
        val delivered = mutableListOf<PhoneHudMessage>()
        val acknowledgments = mutableListOf<String>()
        val metrics = HudRuntimeMetrics()
        val controller = HudPhoneMessageController(
            metrics = metrics,
            acknowledge = acknowledgments::add,
            deliver = delivered::add,
        )

        val result = controller.accept(
            """{"type":"run_state","state":"streaming","canAbort":true,"_tx":"tx-1"}""",
        )

        assertEquals(HudPhoneMessageResult.Delivered, result)
        assertTrue(delivered.single() is PhoneHudMessage.RunState)
        assertEquals(listOf("tx-1"), acknowledgments)
        assertEquals(1, metrics.snapshot().phoneMessages)
    }

    @Test
    fun `duplicate transaction is acknowledged without a second delivery`() {
        var deliveries = 0
        val acknowledgments = mutableListOf<String>()
        val metrics = HudRuntimeMetrics()
        val controller = HudPhoneMessageController(
            metrics = metrics,
            acknowledge = acknowledgments::add,
            deliver = { deliveries += 1 },
        )
        val payload =
            """{"type":"run_state","state":"idle","canAbort":false,"_tx":"tx-2"}"""

        controller.accept(payload)
        val duplicate = controller.accept(payload)

        assertEquals(HudPhoneMessageResult.Duplicate("tx-2"), duplicate)
        assertEquals(1, deliveries)
        assertEquals(listOf("tx-2", "tx-2"), acknowledgments)
        assertEquals(1, metrics.snapshot().duplicateTransactions)
    }

    @Test
    fun `malformed and unknown packets retain forward-compatible acknowledgments`() {
        val acknowledgments = mutableListOf<String>()
        val metrics = HudRuntimeMetrics()
        val controller = HudPhoneMessageController(
            metrics = metrics,
            acknowledge = acknowledgments::add,
            deliver = { error("must not deliver") },
        )

        val malformed = controller.accept(
            """{"type":"run_state","state":"idle","canAbort":"false","_tx":"bad"}""",
        )
        val unknown = controller.accept("""{"type":"future_type","_tx":"future"}""")

        assertTrue(malformed is HudPhoneMessageResult.Malformed)
        assertEquals(HudPhoneMessageResult.Unknown("future_type"), unknown)
        assertEquals(listOf("bad", "future"), acknowledgments)
        assertEquals(1, metrics.snapshot().malformedMessages)
    }

    @Test
    fun `handler failure is not acknowledged and can be replayed`() {
        var attempts = 0
        val acknowledgments = mutableListOf<String>()
        val controller = HudPhoneMessageController(
            metrics = HudRuntimeMetrics(),
            acknowledge = acknowledgments::add,
            deliver = {
                attempts += 1
                error("effect failed")
            },
        )
        val payload =
            """{"type":"connection_update","connected":true,"_tx":"retry"}"""

        assertTrue(controller.accept(payload) is HudPhoneMessageResult.HandlerFailed)
        assertTrue(controller.accept(payload) is HudPhoneMessageResult.HandlerFailed)
        assertEquals(2, attempts)
        assertTrue(acknowledgments.isEmpty())
    }
}
