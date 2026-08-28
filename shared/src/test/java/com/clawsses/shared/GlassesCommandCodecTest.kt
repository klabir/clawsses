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
