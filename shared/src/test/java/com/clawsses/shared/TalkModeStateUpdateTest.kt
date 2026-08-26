package com.clawsses.shared

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class TalkModeStateUpdateTest {
    @Test
    fun serializedStateCarriesInteractionMode() {
        val json = JsonParser.parseString(
            TalkModeStateUpdate(
                enabled = true,
                phase = "listening",
                mode = "follow_up",
                interruptible = false,
            ).toJson(),
        ).asJsonObject

        assertEquals("talk_mode_state", json["type"].asString)
        assertEquals("follow_up", json["mode"].asString)
    }
}
