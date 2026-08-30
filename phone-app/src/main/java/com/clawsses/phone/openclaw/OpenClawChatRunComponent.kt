package com.clawsses.phone.openclaw

import com.clawsses.phone.media.ChatAttachmentFileStore
import com.clawsses.shared.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Owns chat history, streaming and active-run state behind the client facade. */
internal class OpenClawChatRunComponent(
    attachmentFileStore: ChatAttachmentFileStore,
) {
    val chatStore = BoundedChatStore(onMessagesChanged = attachmentFileStore::retainOnly)
    val runState = MutableStateFlow(OpenClawClient.RunState.IDLE)
    val runError = MutableStateFlow<String?>(null)
    val completedAbortedRuns = ConcurrentHashMap<String, Long>()
    val streamUpdateBuffer = StreamUpdateBuffer()

    @Volatile var activeRunId: String? = null
    @Volatile var activeMessageId: String? = null
    @Volatile var activeSessionKey: String? = null
    @Volatile var abortingRunId: String? = null
    var streamingContent = ""
    var lastAgentPhase: String? = null
    @Volatile var agentProgressActive = false

    fun rememberAbortedRun(runId: String?, nowMs: Long = System.currentTimeMillis()) {
        if (runId == null) return
        completedAbortedRuns[runId] = nowMs
        if (completedAbortedRuns.size > MAX_COMPLETED_ABORTS) {
            completedAbortedRuns.entries
                .sortedBy { it.value }
                .take(completedAbortedRuns.size - MAX_COMPLETED_ABORTS)
                .forEach { completedAbortedRuns.remove(it.key, it.value) }
        }
    }

    fun resetActiveRun() {
        activeRunId = null
        activeMessageId = null
        activeSessionKey = null
        abortingRunId = null
        streamingContent = ""
        streamUpdateBuffer.reset()
        lastAgentPhase = null
    }

    fun add(message: ChatMessage) = chatStore.add(message)
    fun upsertCompleted(message: ChatMessage) = chatStore.upsertCompleted(message)
    fun updateStreaming(messageId: String, fullText: String) =
        chatStore.updateStreaming(messageId, fullText)

    private companion object {
        const val MAX_COMPLETED_ABORTS = 64
    }
}
