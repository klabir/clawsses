package com.clawsses.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesCommandCodecTest {
    @Test
    fun decodesTypedCommands() {
        assertEquals(
            GlassesCommandDecodeResult.Success(GlassesCommand.SwitchSession("agent:main:main")),
            GlassesCommandCodec.decode("""{"type":"switch_session","sessionKey":"agent:main:main"}"""),
        )
        assertEquals(
            GlassesCommandDecodeResult.Success(GlassesCommand.TakePhoto(true, "read this")),
            GlassesCommandCodec.decode(
                """{"type":"take_photo","sendAfterCapture":true,"visionPrompt":"read this"}""",
            ),
        )
        assertEquals(
            GlassesCommandDecodeResult.Success(GlassesCommand.UserInput("hello", "local-1")),
            GlassesCommandCodec.decode(
                """{"type":"user_input","text":"hello","id":"local-1"}""",
            ),
        )
        assertEquals(
            GlassesCommandDecodeResult.Success(GlassesCommand.RequestMoreHistory("oldest")),
            GlassesCommandCodec.decode(
                """{"type":"request_more_history","beforeMessageId":"oldest"}""",
            ),
        )
    }

    @Test
    fun preservesLegacyDefaults() {
        assertEquals(
            GlassesCommandDecodeResult.Success(GlassesCommand.ListSessions(0)),
            GlassesCommandCodec.decode("""{"type":"list_sessions"}"""),
        )
        assertEquals(
            GlassesCommandDecodeResult.Success(GlassesCommand.RemovePhoto(false, null)),
            GlassesCommandCodec.decode("""{"type":"remove_photo"}"""),
        )
        assertEquals(
            GlassesCommandDecodeResult.Success(GlassesCommand.UserInput("hello", null)),
            GlassesCommandCodec.decode("""{"type":"user_input","text":"hello"}"""),
        )
        assertEquals(
            GlassesCommandDecodeResult.Success(GlassesCommand.RequestState(90)),
            GlassesCommandCodec.decode("""{"type":"request_state","versionCode":90}"""),
        )
    }

    @Test
    fun decodesExplicitPeerContract() {
        assertEquals(
            GlassesCommandDecodeResult.Success(
                GlassesCommand.RequestState(
                    versionCode = 93,
                    versionName = "1.3.84",
                    protocolVersion = 1,
                    capabilities = setOf("transport_ack", "model_paging"),
                ),
            ),
            GlassesCommandCodec.decode(
                """{"type":"request_state","versionName":"1.3.84","versionCode":93,"protocolVersion":1,"capabilities":["transport_ack","model_paging"]}""",
            ),
        )
    }

    @Test
    fun rejectsMissingAndWronglyTypedRequiredFields() {
        val missing = GlassesCommandCodec.decode("""{"type":"switch_session"}""")
        val wrongType = GlassesCommandCodec.decode("""{"type":"tts_toggle","enabled":"yes"}""")

        assertTrue(missing is GlassesCommandDecodeResult.Malformed)
        assertTrue(wrongType is GlassesCommandDecodeResult.Malformed)
    }

    @Test
    fun distinguishesUnknownTypeFromMalformedJson() {
        assertEquals(
            GlassesCommandDecodeResult.UnknownType("future_command"),
            GlassesCommandCodec.decode("""{"type":"future_command"}"""),
        )
        assertTrue(GlassesCommandCodec.decode("not-json") is GlassesCommandDecodeResult.Malformed)
    }
}
