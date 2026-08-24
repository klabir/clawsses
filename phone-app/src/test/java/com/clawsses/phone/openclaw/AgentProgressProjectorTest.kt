package com.clawsses.phone.openclaw

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProgressProjectorTest {
    @Test
    fun `reasoning content never crosses the glasses protocol`() {
        val data = JsonParser.parseString(
            """{"text":"SECRET chain of thought","delta":"private details"}"""
        ).asJsonObject

        val update = AgentProgressProjector.project("thinking", data)

        assertNotNull(update)
        assertEquals("Reasoning…", update!!.label)
        assertFalse(update.toJson().contains("SECRET"))
        assertFalse(update.toJson().contains("private details"))
    }

    @Test
    fun `tool projection excludes arguments results paths and errors`() {
        val data = JsonParser.parseString(
            """
            {
              "phase":"result",
              "name":"read_file",
              "toolCallId":"tool-1",
              "args":{"path":"/secret/private.txt"},
              "result":"API_KEY=secret",
              "isError":false
            }
            """.trimIndent()
        ).asJsonObject

        val update = AgentProgressProjector.project("tool", data)!!
        val json = update.toJson()

        assertEquals("Reading complete", update.label)
        assertEquals("done", update.state)
        assertFalse(json.contains("/secret"))
        assertFalse(json.contains("API_KEY"))
        assertTrue(json.toByteArray(Charsets.UTF_8).size < 500)
    }

    @Test
    fun `plan projection shows only the current concise step`() {
        val data = JsonParser.parseString(
            """
            {
              "phase":"update",
              "steps":[
                {"step":"Inspect implementation","status":"completed"},
                {"step":"  Build\n and test the HUD  ","status":"in_progress"},
                {"step":"Install","status":"pending"}
              ]
            }
            """.trimIndent()
        ).asJsonObject

        val update = AgentProgressProjector.project("plan", data)!!

        assertEquals("Build and test the HUD", update.label)
        assertEquals("active", update.state)
        assertTrue(update.toJson().toByteArray(Charsets.UTF_8).size < 500)
    }
}
