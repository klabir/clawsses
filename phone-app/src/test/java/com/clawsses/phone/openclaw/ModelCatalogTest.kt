package com.clawsses.phone.openclaw

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun `parses configured model refs and availability`() {
        val payload = JsonParser.parseString(
            """
            {
              "models": [
                {
                  "provider": "openai",
                  "id": "gpt-5.6-sol",
                  "name": "GPT-5.6 Sol",
                  "available": true
                },
                {
                  "provider": "openai",
                  "id": "gpt-5.6",
                  "name": "GPT-5.6",
                  "available": false
                }
              ]
            }
            """.trimIndent()
        ).asJsonObject

        val result = parseConfiguredModels(payload)

        assertEquals(2, result.size)
        assertEquals("openai/gpt-5.6-sol", result.first().ref)
        assertEquals("GPT-5.6 Sol", result.first().name)
        assertFalse(result.last().available)
    }

    @Test
    fun `resolves current session model against configured catalog`() {
        val sessions = JsonParser.parseString(
            """
            {
              "sessions": [
                {
                  "key": "agent:main:main",
                  "modelProvider": "openai",
                  "model": "gpt-5.6-sol"
                }
              ]
            }
            """.trimIndent()
        ).asJsonObject
        val models = listOf(
            com.clawsses.shared.ModelInfo(
                ref = "openai/gpt-5.6-sol",
                provider = "openai",
                id = "gpt-5.6-sol",
                name = "GPT-5.6 Sol",
            )
        )

        assertEquals(
            "openai/gpt-5.6-sol",
            resolveSessionModelRef(sessions, "agent:main:main", models),
        )
    }

    @Test
    fun `agent list reports active session model without changing other agents`() {
        val result = buildAgentListUpdate(
            agents = listOf(
                com.clawsses.shared.AgentInfo("main", "Main", "openai/gpt-5.6-sol"),
                com.clawsses.shared.AgentInfo("other", "Other", null),
            ),
            currentAgentId = "main",
            currentModelRef = "ollama/qwen3.5:35b",
        )

        assertEquals("ollama/qwen3.5:35b", result.agents.first().model)
        assertNull(result.agents.last().model)
        assertEquals("main", result.currentAgentId)
    }
}
