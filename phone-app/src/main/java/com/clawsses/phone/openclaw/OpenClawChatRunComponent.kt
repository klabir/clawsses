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

    fun plan(event: ParsedChatEvent, currentSessionKey: String?): ChatEventPlan {
        val runId = event.runId
        if (runId != null && completedAbortedRuns.containsKey(runId)) return ChatEventPlan.Ignore

        val eventSessionKey = event.sessionKey
        if (eventSessionKey != null && currentSessionKey != null &&
            eventSessionKey != currentSessionKey
        ) {
            return ChatEventPlan.InactiveSession(
                sessionKey = eventSessionKey,
                terminalActiveRun = runId != null && runId == activeRunId && event.state.isTerminal(),
                terminalState = event.state,
            )
        }
        if (runId != null && activeRunId != null && runId != activeRunId) {
            return ChatEventPlan.Ignore
        }
        val messageId = activeMessageId ?: return ChatEventPlan.Ignore
        return when (event.state) {
            "delta" -> {
                if (runId == abortingRunId || event.fullText.length <= streamingContent.length) {
                    ChatEventPlan.Ignore
                } else {
                    ChatEventPlan.Delta(
                        messageId = messageId,
                        fullText = event.fullText,
                        newChunk = event.fullText.substring(streamingContent.length),
                    )
                }
            }
            "final" -> {
                if (runId == abortingRunId) {
                    ChatEventPlan.Terminal(
                        messageId = messageId,
                        state = "aborted",
                        rememberAbortedRunId = runId,
                    )
                } else {
                    ChatEventPlan.Final(
                        messageId = messageId,
                        fullText = event.fullText,
                        newChunk = event.fullText.takeIf { it.length > streamingContent.length }
                            ?.substring(streamingContent.length),
                    )
                }
            }
            "aborted", "error" -> ChatEventPlan.Terminal(
                messageId = messageId,
                state = event.state,
                errorMessage = event.errorMessage,
                rememberAbortedRunId = runId.takeIf { event.state == "aborted" },
            )
            else -> ChatEventPlan.Ignore
        }
    }

    private companion object {
        const val MAX_COMPLETED_ABORTS = 64
    }
}

internal sealed interface ChatEventPlan {
    data object Ignore : ChatEventPlan
    data class InactiveSession(
        val sessionKey: String,
        val terminalActiveRun: Boolean,
        val terminalState: String,
    ) : ChatEventPlan
    data class Delta(val messageId: String, val fullText: String, val newChunk: String) :
        ChatEventPlan
    data class Final(val messageId: String, val fullText: String, val newChunk: String?) :
        ChatEventPlan
    data class Terminal(
        val messageId: String,
        val state: String,
        val errorMessage: String? = null,
        val rememberAbortedRunId: String? = null,
    ) : ChatEventPlan
}

private fun String.isTerminal(): Boolean = this == "final" || this == "aborted" || this == "error"
