package com.clawsses.glasses.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHudMessageCodecTest {
    @Test
    fun decodesCompactHistoryAndTransportEnvelope() {
        val result = PhoneHudMessageCodec.decode(
            """{"type":"chat_history","_tx":"7","isLoadMore":true,"hasMore":false,"messages":[{"i":"m1","r":"u","c":"hello"}]}""",
        ) as PhoneHudDecodeResult.Success
        val history = result.envelope.message as PhoneHudMessage.History

        assertEquals("7", result.envelope.transactionId)
        assertTrue(history.isLoadMore)
        assertEquals("user", history.messages.single().role)
        assertEquals("hello", history.messages.single().content)
    }

    @Test
    fun rejectsActionableWrongFieldTypes() {
        val result = PhoneHudMessageCodec.decode(
            """{"type":"run_state","state":"streaming","canAbort":"true"}""",
        )

        assertTrue(result is PhoneHudDecodeResult.Malformed)
    }

    @Test
    fun keepsUnknownFutureMessageForCompatibilityHandler() {
        val result = PhoneHudMessageCodec.decode("""{"type":"future_update","_tx":"9"}""")

        assertEquals("9", (result as PhoneHudDecodeResult.UnknownType).transactionId)
    }

    @Test
    fun requiresStableIdsForStreamingMessages() {
        val result = PhoneHudMessageCodec.decode("""{"type":"chat_stream","id":"","chunk":"x"}""")

        assertTrue(result is PhoneHudDecodeResult.Malformed)
    }
}
