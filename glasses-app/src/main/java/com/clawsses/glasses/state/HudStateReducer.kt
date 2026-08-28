package com.clawsses.glasses.state

import com.clawsses.glasses.ui.AgentPickerInfo
import com.clawsses.glasses.ui.AgentProgressDisplay
import com.clawsses.glasses.ui.AgentState
import com.clawsses.glasses.ui.ChatHudState
import com.clawsses.glasses.ui.DisplayMessage
import com.clawsses.glasses.ui.LiveCaptionDisplay
import com.clawsses.glasses.ui.SessionPickerInfo

sealed interface HudStateEvent {
    data class MessageCompleted(val message: DisplayMessage) : HudStateEvent
    data class HistoryLoaded(
        val messages: List<DisplayMessage>,
        val isLoadMore: Boolean,
        val hasMore: Boolean,
    ) : HudStateEvent

    data class StreamCompleted(
        val id: String,
        val content: String?,
    ) : HudStateEvent

    data class ConnectionChanged(
        val connected: Boolean,
        val sessionKey: String?,
        val sessionName: String?,
    ) : HudStateEvent

    data class SessionsLoaded(
        val sessions: List<SessionPickerInfo>,
        val currentSessionKey: String?,
    ) : HudStateEvent

    data class AgentsLoaded(
        val agents: List<AgentPickerInfo>,
        val currentAgentId: String?,
        val showPicker: Boolean,
    ) : HudStateEvent

    data class AgentPhaseChanged(val phase: String) : HudStateEvent
    data class AgentProgressChanged(
        val id: String,
        val kind: String,
        val label: String,
        val state: String,
    ) : HudStateEvent

    data class RunChanged(val state: String, val canAbort: Boolean) : HudStateEvent
    data class TalkModeChanged(val enabled: Boolean, val phase: String) : HudStateEvent
    data class LiveCaptionChanged(
        val enabled: Boolean,
        val caption: LiveCaptionDisplay?,
    ) : HudStateEvent
}

data class HudStateReduction(
    val state: ChatHudState,
    val prependedCount: Int = 0,
)

/**
 * Pure owner of Phone-to-HUD state transitions.
 *
 * JSON decoding, lifecycle jobs, bitmap decoding, and transport acknowledgements remain in
 * [com.clawsses.glasses.HudActivity]. Keeping those effects outside this reducer makes state
 * transitions deterministic and independently regression-testable.
 */
object HudStateReducer {
    fun reduce(current: ChatHudState, event: HudStateEvent): HudStateReduction = when (event) {
        is HudStateEvent.MessageCompleted -> reduceCompletedMessage(current, event.message)
        is HudStateEvent.HistoryLoaded -> reduceHistory(current, event)
        is HudStateEvent.StreamCompleted -> reduceCompletedStream(current, event)
        is HudStateEvent.ConnectionChanged -> reduceConnection(current, event)
        is HudStateEvent.SessionsLoaded -> reduceSessions(current, event)
        is HudStateEvent.AgentsLoaded -> reduceAgents(current, event)
        is HudStateEvent.AgentPhaseChanged -> HudStateReduction(
            current.copy(agentState = event.phase.toAgentPhaseState())
        )
        is HudStateEvent.AgentProgressChanged -> reduceAgentProgress(current, event)
        is HudStateEvent.RunChanged -> HudStateReduction(
            current.copy(
                runState = event.state,
                runCanAbort = event.canAbort,
                agentState = event.state.toAgentState(),
            )
        )
        is HudStateEvent.TalkModeChanged -> HudStateReduction(
            current.copy(
                talkModeEnabled = event.enabled,
                talkModePhase = event.phase,
            )
        )
        is HudStateEvent.LiveCaptionChanged -> HudStateReduction(
            current.copy(
                liveCaptionEnabled = event.enabled,
                liveCaption = event.caption.takeIf { event.enabled },
            )
        )
    }

    private fun reduceCompletedMessage(
        current: ChatHudState,
        incoming: DisplayMessage,
    ): HudStateReduction {
        val isDuplicateUserEcho = incoming.role == "user" && current.messages.any {
            it.role == "user" && it.content == incoming.content
        }
        if (isDuplicateUserEcho) {
            return HudStateReduction(
                if (current.photoThumbnails.isEmpty()) current
                else current.copy(photoThumbnails = emptyList())
            )
        }

        val completed = incoming.copy(
            isStreaming = false,
            thumbnails = if (incoming.role == "user" && incoming.thumbnails.isEmpty()) {
                current.photoThumbnails.toList()
            } else {
                incoming.thumbnails
            },
        )
        val messages = current.messages.toMutableList()
        val existingIndex = messages.indexOfFirst { it.id == completed.id }
        if (completed.role != "user" && existingIndex >= 0) {
            messages[existingIndex] = completed
        } else {
            messages += completed
        }
        return HudStateReduction(
            current.copy(
                messages = messages,
                agentState = AgentState.IDLE,
                photoThumbnails = if (completed.role == "user") emptyList() else current.photoThumbnails,
                scrollPosition = if (current.isScrolledToEnd) messages.lastIndex else current.scrollPosition,
                scrollTrigger = current.scrollTrigger + 1,
            )
        )
    }

    private fun reduceHistory(
        current: ChatHudState,
        event: HudStateEvent.HistoryLoaded,
    ): HudStateReduction {
        if (event.isLoadMore && current.isLoadingMoreHistory) {
            val prependedCount = (event.messages.size - current.messages.size).coerceAtLeast(0)
            return if (prependedCount == 0) {
                HudStateReduction(
                    current.copy(
                        messages = event.messages,
                        isLoadingMoreHistory = false,
                        hasMoreHistory = false,
                        newPrependCount = 0,
                    )
                )
            } else {
                HudStateReduction(
                    state = current.copy(
                        messages = event.messages,
                        scrollPosition = current.scrollPosition + prependedCount,
                        isLoadingMoreHistory = false,
                        hasMoreHistory = event.hasMore,
                        newPrependCount = prependedCount,
                    ),
                    prependedCount = prependedCount,
                )
            }
        }

        return HudStateReduction(
            current.copy(
                messages = event.messages,
                agentState = AgentState.IDLE,
                scrollPosition = event.messages.lastIndex.coerceAtLeast(0),
                scrollTrigger = current.scrollTrigger + 1,
                isLoadingMoreHistory = false,
                hasMoreHistory = event.hasMore,
            )
        )
    }

    private fun reduceCompletedStream(
        current: ChatHudState,
        event: HudStateEvent.StreamCompleted,
    ): HudStateReduction {
        val messages = current.messages.toMutableList()
        if (event.content != null) {
            val existingIndex = messages.indexOfFirst { it.id == event.id }
            if (existingIndex >= 0) {
                messages[existingIndex] = messages[existingIndex].copy(
                    content = event.content,
                    isStreaming = false,
                )
            } else {
                messages += DisplayMessage(
                    id = event.id,
                    role = "assistant",
                    content = event.content,
                    isStreaming = false,
                )
            }
        }
        return HudStateReduction(
            current.copy(
                messages = messages,
                agentState = AgentState.IDLE,
                agentProgress = emptyList(),
            )
        )
    }

    private fun reduceConnection(
        current: ChatHudState,
        event: HudStateEvent.ConnectionChanged,
    ): HudStateReduction {
        val sessionKey = event.sessionKey ?: current.currentSessionKey
        val sessionName = event.sessionName ?: current.currentSessionName
        val sessionChanged = sessionKey != current.currentSessionKey
        return HudStateReduction(
            current.copy(
                isConnected = event.connected,
                currentSessionKey = sessionKey,
                currentSessionName = sessionName,
                currentAgentId = agentIdFromSessionKey(sessionKey) ?: current.currentAgentId,
                currentAgentName = sessionName?.takeIf { it.isNotBlank() } ?: current.currentAgentName,
                showSessionPicker = if (sessionChanged) false else current.showSessionPicker,
                isSessionOperationPending = if (sessionChanged) false else current.isSessionOperationPending,
                sessionOperationMessage = if (sessionChanged) null else current.sessionOperationMessage,
                sessionOperationError = if (sessionChanged) null else current.sessionOperationError,
            )
        )
    }

    private fun reduceSessions(
        current: ChatHudState,
        event: HudStateEvent.SessionsLoaded,
    ): HudStateReduction {
        val currentSessionKey = event.currentSessionKey ?: current.currentSessionKey
        val selectedIndex = event.sessions.indexOfFirst { it.key == currentSessionKey }.coerceAtLeast(0)
        val resolvedName = event.sessions.firstOrNull { it.key == currentSessionKey }?.name
            ?: current.currentSessionName
        return HudStateReduction(
            current.copy(
                availableSessions = event.sessions,
                currentSessionKey = currentSessionKey,
                currentSessionName = resolvedName,
                selectedSessionIndex = selectedIndex,
                isSessionOperationPending = false,
                sessionOperationMessage = null,
                sessionOperationError = null,
            )
        )
    }

    private fun reduceAgents(
        current: ChatHudState,
        event: HudStateEvent.AgentsLoaded,
    ): HudStateReduction {
        val currentAgentId = event.currentAgentId ?: agentIdFromSessionKey(current.currentSessionKey)
        val selectedIndex = event.agents.indexOfFirst { it.id == currentAgentId }.coerceAtLeast(0)
        return HudStateReduction(
            current.copy(
                showAgentPicker = event.showPicker,
                availableAgents = event.agents,
                currentAgentId = currentAgentId,
                currentAgentName = event.agents.firstOrNull { it.id == currentAgentId }?.name
                    ?: current.currentAgentName,
                selectedAgentIndex = selectedIndex,
            )
        )
    }

    private fun reduceAgentProgress(
        current: ChatHudState,
        event: HudStateEvent.AgentProgressChanged,
    ): HudStateReduction {
        if (event.state == "clear") {
            return HudStateReduction(current.copy(agentProgress = emptyList()))
        }
        val label = event.label.trim()
        if (event.id.isBlank() || label.isBlank()) return HudStateReduction(current)
        val updated = current.agentProgress
            .filterNot { it.id == event.id }
            .plus(
                AgentProgressDisplay(
                    id = event.id,
                    kind = event.kind,
                    label = label.take(96),
                    state = event.state,
                )
            )
            .takeLast(3)
        return HudStateReduction(current.copy(agentProgress = updated))
    }

    private fun String.toAgentState(): AgentState = when (this) {
        "reasoning" -> AgentState.REASONING
        "streaming" -> AgentState.STREAMING
        "aborting" -> AgentState.ABORTING
        "waiting", "thinking" -> AgentState.THINKING
        else -> AgentState.IDLE
    }

    private fun String.toAgentPhaseState(): AgentState = when (this) {
        "reasoning" -> AgentState.REASONING
        "aborting" -> AgentState.ABORTING
        else -> AgentState.THINKING
    }

    private fun agentIdFromSessionKey(sessionKey: String?): String? {
        val parts = sessionKey?.split(':') ?: return null
        return parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "agent" && it.isNotBlank() }
    }
}
