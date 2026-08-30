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

    @Test
    fun decodesPhonePeerContract() {
        val result = PhoneHudMessageCodec.decode(
            """{"type":"peer_state","versionName":"1.3.84","versionCode":93,"protocolVersion":1,"capabilities":["chunked_history","model_paging"]}""",
        ) as PhoneHudDecodeResult.Success
        val peer = result.envelope.message as PhoneHudMessage.PeerState

        assertEquals(93, peer.versionCode)
        assertEquals(setOf("chunked_history", "model_paging"), peer.capabilities)
    }

    @Test
    fun `every production phone message has a typed decoder`() {
        val payloads = listOf(
            """{"type":"session_list","sessions":[{"k":"main","n":"Main","u":true}],"hasMore":true,"nextOffset":3}""",
            """{"type":"session_operation","operation":"create","state":"loading"}""",
            """{"type":"model_page","c":"catalog","m":[{"i":1,"n":"Model","p":"Provider","a":true}],"o":0,"pi":0,"pc":1,"ci":1}""",
            """{"type":"model_operation","state":"success","ci":1}""",
            """{"type":"agent_list","agents":[{"id":"main","name":"Main"}],"currentAgentId":"main"}""",
            """{"type":"voice_state","state":"recognizing","text":"hello","mode":"staged"}""",
            """{"type":"voice_result","result_type":"text","text":"hello","autoSent":false}""",
            """{"type":"photo_result","status":"captured","thumbnail":"AA==","thumbnailFormat":"mono1","thumbnailWidth":1,"thumbnailHeight":1}""",
            """{"type":"remove_photo","index":0}""",
            """{"type":"wake_signal","reason":"new_message","bufferedCount":1}""",
            """{"type":"tts_state","enabled":true,"voiceName":"Voice","playbackState":"playing","canReplay":true}""",
            """{"type":"talk_mode_state","enabled":true,"phase":"listening"}""",
            """{"type":"hud_card","id":"card","source":"Clawsses","title":"Title","body":"Body","actions":[{"id":"open","label":"Open"}]}""",
            """{"type":"live_caption","enabled":true,"sourceText":"Hallo","translatedText":"Hello"}""",
        )

        payloads.forEach { payload ->
            assertTrue("Expected typed decode for $payload", PhoneHudMessageCodec.decode(payload) is PhoneHudDecodeResult.Success)
        }
    }

    @Test
    fun `new typed catalog fields reject wrong primitive kinds`() {
        val result = PhoneHudMessageCodec.decode(
            """{"type":"model_page","m":[{"i":"one"}],"pc":1}""",
        )

        assertTrue(result is PhoneHudDecodeResult.Malformed)
    }
}
