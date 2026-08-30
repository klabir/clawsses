package com.clawsses.phone.openclaw

import com.clawsses.phone.media.ChatAttachmentFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import com.google.gson.JsonParser
import com.clawsses.shared.AgentInfo
import com.clawsses.shared.ModelInfo

class OpenClawComponentsTest {
    @Test
    fun `catalog session activation invalidates old work and resets paging`() {
        val component = OpenClawCatalogSessionComponent()
        val oldOperation = component.activateSession("agent:main:first")
        component.currentHistoryLimit = 250
        component.hasMoreHistory.value = false
        component.isLoadingMoreHistory.value = true
        component.unreadSessions.value = setOf("agent:main:first", "agent:main:second")

        val currentOperation = component.activateSession("agent:main:second")

        assertFalse(component.isCurrentOperation("agent:main:first", oldOperation))
        assertTrue(component.isCurrentOperation("agent:main:second", currentOperation))
        assertEquals(50, component.currentHistoryLimit)
        assertTrue(component.hasMoreHistory.value)
        assertFalse(component.isLoadingMoreHistory.value)
        assertEquals(setOf("agent:main:first"), component.unreadSessions.value)
    }

    @Test
    fun `chat run reset clears transient state but keeps bounded abort history`() {
        val directory = Files.createTempDirectory("clawsses-chat-run-test").toFile()
        val component = OpenClawChatRunComponent(ChatAttachmentFileStore(directory))
        component.activeRunId = "run"
        component.activeMessageId = "message"
        component.activeSessionKey = "session"
        component.abortingRunId = "run"
        component.streamingContent = "partial"
        component.lastAgentPhase = "reasoning"
        repeat(70) { component.rememberAbortedRun("run-$it", nowMs = it.toLong()) }

        component.resetActiveRun()

        assertNull(component.activeRunId)
        assertNull(component.activeMessageId)
        assertNull(component.activeSessionKey)
        assertNull(component.abortingRunId)
        assertEquals("", component.streamingContent)
        assertNull(component.lastAgentPhase)
        assertEquals(64, component.completedAbortedRuns.size)
        assertFalse(component.completedAbortedRuns.containsKey("run-0"))
        assertTrue(component.completedAbortedRuns.containsKey("run-69"))
        directory.deleteRecursively()
    }

    @Test
    fun `agent catalog applies identity labels and primary model`() {
        val payload = JsonParser.parseString(
            """{"defaultId":"main","agents":[
                {"id":"main","name":"Bugl","identity":{"emoji":"tool"},"model":{"primary":"openai/codex"}},
                {"id":"secondary","identity":{"name":"Second","emoji":"(not set)"},"model":"local/model"}
            ]}""",
        ).asJsonObject

        val parsed = parseAgentCatalog(payload)

        assertEquals("main", parsed.defaultAgentId)
        assertEquals("tool Bugl", parsed.agents[0].name)
        assertEquals("openai/codex", parsed.agents[0].model)
        assertEquals("Second", parsed.agents[1].name)
    }

    @Test
    fun `model catalog prefers session model then active agent fallback`() {
        val component = OpenClawCatalogSessionComponent()
        component.activateSession("agent:main:main")
        component.agentList.value = listOf(AgentInfo("main", "Main", "local/fallback"))
        val models = listOf(
            ModelInfo("openai/current", "openai", "current", "Current", true),
            ModelInfo("local/fallback", "local", "fallback", "Fallback", true),
        )

        val sessionCatalog = component.applyModelCatalog(models, "openai/current")
        val fallbackCatalog = component.applyModelCatalog(models, null)

        assertEquals("openai/current", sessionCatalog.currentModel)
        assertEquals("local/fallback", fallbackCatalog.currentModel)
        assertEquals(models, component.modelList.value)
    }

    @Test
    fun `chat planner rejects stale runs and emits only the appended delta`() {
        val directory = Files.createTempDirectory("clawsses-chat-plan-test").toFile()
        val component = OpenClawChatRunComponent(ChatAttachmentFileStore(directory)).apply {
            activeRunId = "current"
            activeMessageId = "message"
            activeSessionKey = "agent:main:main"
            streamingContent = "Hello"
        }

        val stale = component.plan(
            ParsedChatEvent("delta", "old", "agent:main:main", "ignored", null),
            "agent:main:main",
        )
        val delta = component.plan(
            ParsedChatEvent("delta", "current", "agent:main:main", "Hello world", null),
            "agent:main:main",
        )

        assertEquals(ChatEventPlan.Ignore, stale)
        assertEquals(ChatEventPlan.Delta("message", "Hello world", " world"), delta)
        directory.deleteRecursively()
    }

    @Test
    fun `chat planner separates inactive terminal events and abort races`() {
        val directory = Files.createTempDirectory("clawsses-chat-terminal-test").toFile()
        val component = OpenClawChatRunComponent(ChatAttachmentFileStore(directory)).apply {
            activeRunId = "run"
            activeMessageId = "message"
            activeSessionKey = "agent:main:old"
            abortingRunId = "run"
        }

        val inactive = component.plan(
            ParsedChatEvent("error", "run", "agent:main:old", "", "failed"),
            "agent:main:new",
        )
        val abortedFinal = component.plan(
            ParsedChatEvent("final", "run", "agent:main:new", "late", null),
            "agent:main:new",
        )

        assertEquals(ChatEventPlan.InactiveSession("agent:main:old", true, "error"), inactive)
        assertEquals(
            ChatEventPlan.Terminal("message", "aborted", rememberAbortedRunId = "run"),
            abortedFinal,
        )
        directory.deleteRecursively()
    }
}
