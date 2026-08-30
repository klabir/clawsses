package com.clawsses.phone.openclaw

import com.clawsses.phone.media.ChatAttachmentFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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
}
