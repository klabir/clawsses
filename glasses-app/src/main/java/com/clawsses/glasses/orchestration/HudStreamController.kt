package com.clawsses.glasses.orchestration

import com.clawsses.glasses.ui.AgentState
import com.clawsses.glasses.ui.ChatHudState
import com.clawsses.glasses.ui.HudStreamingAccumulator
import com.clawsses.glasses.ui.HudStreamingSnapshot

internal data class HudStreamChunkDecision(
    val state: ChatHudState,
    val publishImmediately: Boolean,
    val schedulePublication: Boolean,
)

/** Owns deterministic HUD stream accumulation while the Activity owns lifecycle jobs. */
internal class HudStreamController(
    private val accumulator: HudStreamingAccumulator = HudStreamingAccumulator(),
) {
    fun acceptChunk(
        state: ChatHudState,
        messageId: String,
        chunk: String,
        publicationPending: Boolean,
    ): HudStreamChunkDecision {
        val startedNewMessage = accumulator.append(messageId, chunk)
        val activateStreaming = startedNewMessage || state.agentState != AgentState.STREAMING
        return HudStreamChunkDecision(
            state = if (activateStreaming) {
                state.copy(agentState = AgentState.STREAMING, agentProgress = emptyList())
            } else {
                state
            },
            publishImmediately = activateStreaming,
            schedulePublication = !activateStreaming &&
                !publicationPending &&
                accumulator.hasUnpublishedChanges(),
        )
    }

    fun snapshotIfChanged(): HudStreamingSnapshot? = accumulator.snapshotIfChanged()

    fun finish(messageId: String): HudStreamingSnapshot? = accumulator.finish(messageId)

    fun clear(messageId: String? = null) {
        accumulator.clear(messageId)
    }
}
