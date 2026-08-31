package com.clawsses.phone.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawWireCodecTest {
    @Test
    fun decodesResponseAndEventFrames() {
        val response = OpenClawWireCodec.decode(
            """{"type":"res","id":"chat-1","ok":true,"payload":{"runId":"run-1"}}""",
        )
        assertTrue(response is GatewayFrame.Response)
        assertEquals("chat-1", (response as GatewayFrame.Response).value.id)

        val event = OpenClawWireCodec.decode(
            """{"type":"event","event":"chat","seq":7,"stateVersion":9,"payload":{"state":"delta"}}""",
        )
        assertTrue(event is GatewayFrame.Event)
        assertEquals(7L, (event as GatewayFrame.Event).value.seq)
        assertEquals(9L, event.value.stateVersion)
    }

    @Test
    fun separatesUnknownAndMalformedFrames() {
        assertTrue(OpenClawWireCodec.decode("""{"type":"future"}""") is GatewayFrame.Unknown)
        assertTrue(OpenClawWireCodec.decode("not-json") is GatewayFrame.Malformed)
        assertTrue(OpenClawWireCodec.decode("""{"type":"res","ok":true}""") is GatewayFrame.Malformed)
    }

    @Test
    fun `advertises the canonical native Android client identity`() {
        val client = OpenClawAuthRequestFactory.clientInfo("1.3.123")

        assertEquals("openclaw-android", client.get("id").asString)
        assertEquals("1.3.123", client.get("version").asString)
        assertEquals("android", client.get("platform").asString)
        assertEquals("ui", client.get("mode").asString)
        assertEquals(setOf("id", "version", "platform", "mode"), client.keySet())
    }

    @Test
    fun parsesChatTextBlocksWithoutTrustingOtherContent() {
        val event = ChatEventParser.parse(
            com.google.gson.JsonParser.parseString(
                """{"state":"final","runId":"run-1","sessionKey":"main","message":{"content":[{"type":"text","text":"hello "},{"type":"tool","text":"private"},{"type":"text","text":"world"}]}}""",
            ).asJsonObject,
        )

        assertEquals("final", event?.state)
        assertEquals("hello world", event?.fullText)
        assertNull(event?.errorMessage)
    }
}
