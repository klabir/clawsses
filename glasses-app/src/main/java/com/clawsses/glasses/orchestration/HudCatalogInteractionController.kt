package com.clawsses.glasses.orchestration

import com.clawsses.glasses.input.GestureHandler.Gesture
import com.clawsses.glasses.input.ModelPageSelection
import com.clawsses.glasses.input.ModelPickerMove
import com.clawsses.glasses.input.ModelPickerNavigation
import com.clawsses.glasses.ui.ChatHudState
import com.clawsses.glasses.ui.ModelPickerInfo
import com.clawsses.glasses.ui.SessionPickerInfo

internal sealed interface HudCatalogAction {
    data class RequestSessions(val offset: Int) : HudCatalogAction
    data object CreateSession : HudCatalogAction
    data class SwitchSession(val sessionKey: String) : HudCatalogAction
    data class RequestModels(
        val offset: Int,
        val pageSelection: ModelPageSelection,
    ) : HudCatalogAction
    data class SelectModel(
        val sessionKey: String,
        val catalogId: String,
        val modelIndex: Int,
    ) : HudCatalogAction
    data object RequestAgents : HudCatalogAction
    data class SwitchAgent(val agentId: String, val agentName: String) : HudCatalogAction
}

internal data class HudCatalogDecision(
    val state: ChatHudState,
    val actions: List<HudCatalogAction> = emptyList(),
    val applyStateBeforeActions: Boolean = true,
    val sessionRequestActive: Boolean? = null,
    val modelRequestActive: Boolean? = null,
    val agentRequestActive: Boolean? = null,
)

/** Pure session, model and agent picker transitions plus typed command intents. */
internal class HudCatalogInteractionController(
    private val newSessionKey: String,
    private val moreSessionsKey: String,
) {
    fun requestSessions(state: ChatHudState, offset: Int = 0): HudCatalogDecision =
        HudCatalogDecision(
            state = state.copy(
                showSessionPicker = true,
                availableSessions = listOf(SessionPickerInfo(newSessionKey, "+ New Session")),
                selectedSessionIndex = 0,
                isSessionOperationPending = true,
                sessionOperationMessage = "Loading sessions...",
                sessionOperationError = null,
            ),
            actions = listOf(HudCatalogAction.RequestSessions(offset.coerceAtLeast(0))),
            sessionRequestActive = true,
        )

    fun planSessionGesture(
        state: ChatHudState,
        gesture: Gesture,
        nextOffset: Int?,
    ): HudCatalogDecision {
        if (state.isSessionOperationPending) {
            return when (gesture) {
                Gesture.TAP -> state.availableSessions
                    .getOrNull(state.selectedSessionIndex)
                    ?.takeIf { it.key == newSessionKey }
                    ?.let { createSession(state) }
                    ?: HudCatalogDecision(state)
                Gesture.DOUBLE_TAP -> closeSessionPicker(state, clearOperation = true)
                else -> HudCatalogDecision(state)
            }
        }

        val optionCount = state.availableSessions.size
        return when (gesture) {
            Gesture.SWIPE_FORWARD -> HudCatalogDecision(
                state.copy(selectedSessionIndex = maxOf(0, state.selectedSessionIndex - 1)),
            )
            Gesture.SWIPE_BACKWARD -> HudCatalogDecision(
                if (optionCount > 0) {
                    state.copy(
                        selectedSessionIndex = minOf(
                            optionCount - 1,
                            state.selectedSessionIndex + 1,
                        ),
                    )
                } else {
                    state
                },
            )
            Gesture.TAP -> state.availableSessions.getOrNull(state.selectedSessionIndex)?.let {
                when (it.key) {
                    newSessionKey -> createSession(state)
                    moreSessionsKey -> requestSessions(state, nextOffset ?: 0)
                    else -> HudCatalogDecision(
                        state = state.copy(
                            showSessionPicker = false,
                            currentSessionName = it.name,
                            sessionOperationError = null,
                        ),
                        actions = listOf(HudCatalogAction.SwitchSession(it.key)),
                        applyStateBeforeActions = false,
                    )
                }
            } ?: closeSessionPicker(state)
            Gesture.DOUBLE_TAP -> closeSessionPicker(state)
            Gesture.LONG_PRESS -> HudCatalogDecision(state)
        }
    }

    fun requestModels(
        state: ChatHudState,
        offset: Int,
        pageSelection: ModelPageSelection = ModelPageSelection.CURRENT,
    ): HudCatalogDecision = HudCatalogDecision(
        state = state.copy(
            showModelPicker = true,
            isModelOperationPending = true,
            modelOperationMessage = "Loading models...",
            modelOperationError = null,
        ),
        actions = listOf(HudCatalogAction.RequestModels(offset, pageSelection)),
        modelRequestActive = true,
    )

    fun planModelGesture(state: ChatHudState, gesture: Gesture): HudCatalogDecision {
        if (state.isModelOperationPending) {
            return if (gesture == Gesture.DOUBLE_TAP) {
                closeModelPicker(state, clearOperation = true)
            }
            else HudCatalogDecision(state)
        }

        return when (gesture) {
            Gesture.SWIPE_FORWARD -> applyModelMove(
                state,
                ModelPickerNavigation.forward(
                    selectedIndex = state.selectedModelIndex,
                    itemCount = state.availableModels.size,
                    nextOffset = state.modelNextOffset,
                ),
            )
            Gesture.SWIPE_BACKWARD -> applyModelMove(
                state,
                ModelPickerNavigation.backward(
                    selectedIndex = state.selectedModelIndex,
                    itemCount = state.availableModels.size,
                    pageOffset = state.modelPageOffset,
                ),
            )
            Gesture.TAP -> selectModel(
                state,
                state.availableModels.getOrNull(state.selectedModelIndex),
            )
            Gesture.DOUBLE_TAP -> closeModelPicker(state)
            Gesture.LONG_PRESS -> HudCatalogDecision(state)
        }
    }

    fun requestAgents(state: ChatHudState): HudCatalogDecision = HudCatalogDecision(
        state = state,
        actions = listOf(HudCatalogAction.RequestAgents),
        agentRequestActive = true,
    )

    fun planAgentGesture(state: ChatHudState, gesture: Gesture): HudCatalogDecision {
        val optionCount = state.availableAgents.size
        return when (gesture) {
            Gesture.SWIPE_FORWARD -> HudCatalogDecision(
                if (optionCount > 0) {
                    state.copy(selectedAgentIndex = maxOf(0, state.selectedAgentIndex - 1))
                } else {
                    state
                },
            )
            Gesture.SWIPE_BACKWARD -> HudCatalogDecision(
                if (optionCount > 0) {
                    state.copy(
                        selectedAgentIndex = minOf(
                            optionCount - 1,
                            state.selectedAgentIndex + 1,
                        ),
                    )
                } else {
                    state
                },
            )
            Gesture.TAP -> state.availableAgents.getOrNull(state.selectedAgentIndex)?.let {
                HudCatalogDecision(
                    state = state.copy(
                        showAgentPicker = false,
                        currentAgentId = it.id,
                        currentAgentName = it.name,
                        currentSessionName = it.name,
                    ),
                    actions = listOf(HudCatalogAction.SwitchAgent(it.id, it.name)),
                    applyStateBeforeActions = false,
                )
            } ?: HudCatalogDecision(state.copy(showAgentPicker = false))
            Gesture.DOUBLE_TAP -> HudCatalogDecision(state.copy(showAgentPicker = false))
            Gesture.LONG_PRESS -> HudCatalogDecision(state)
        }
    }

    private fun createSession(state: ChatHudState): HudCatalogDecision = HudCatalogDecision(
        state = state.copy(
            showSessionPicker = true,
            currentSessionName = null,
            isSessionOperationPending = true,
            sessionOperationMessage = "Creating session...",
            sessionOperationError = null,
        ),
        actions = listOf(HudCatalogAction.CreateSession),
        applyStateBeforeActions = false,
        sessionRequestActive = false,
    )

    private fun closeSessionPicker(
        state: ChatHudState,
        clearOperation: Boolean = false,
    ): HudCatalogDecision = HudCatalogDecision(
        state = if (clearOperation) {
            state.copy(
                showSessionPicker = false,
                isSessionOperationPending = false,
                sessionOperationMessage = null,
                sessionOperationError = null,
            )
        } else {
            state.copy(showSessionPicker = false)
        },
        sessionRequestActive = false,
    )

    private fun applyModelMove(
        state: ChatHudState,
        move: ModelPickerMove,
    ): HudCatalogDecision = when {
        move.selectedIndex != null -> HudCatalogDecision(
            state.copy(selectedModelIndex = move.selectedIndex),
        )
        move.requestedOffset != null -> requestModels(
            state,
            move.requestedOffset,
            move.pageSelection,
        )
        else -> HudCatalogDecision(state)
    }

    private fun selectModel(
        state: ChatHudState,
        selected: ModelPickerInfo?,
    ): HudCatalogDecision = when {
        selected == null -> HudCatalogDecision(state)
        !selected.available -> HudCatalogDecision(
            state.copy(modelOperationError = "Model is unavailable"),
        )
        selected.index == state.currentModelIndex -> closeModelPicker(state)
        state.runState !in setOf("idle", "error") -> HudCatalogDecision(
            state.copy(modelOperationError = "Available after response"),
        )
        state.modelCatalogId == null || state.currentSessionKey == null -> HudCatalogDecision(state)
        else -> HudCatalogDecision(
            state = state.copy(
                isModelOperationPending = true,
                modelOperationMessage = "Changing model...",
                modelOperationError = null,
            ),
            actions = listOf(
                HudCatalogAction.SelectModel(
                    sessionKey = state.currentSessionKey,
                    catalogId = state.modelCatalogId,
                    modelIndex = selected.index,
                ),
            ),
        )
    }

    private fun closeModelPicker(
        state: ChatHudState,
        clearOperation: Boolean = false,
    ): HudCatalogDecision = HudCatalogDecision(
        state = if (clearOperation) {
            state.copy(
                showModelPicker = false,
                isModelOperationPending = false,
                modelOperationMessage = null,
            )
        } else {
            state.copy(showModelPicker = false)
        },
        modelRequestActive = false,
    )
}
