package com.clawsses.glasses.orchestration

import com.clawsses.glasses.input.ModelPageSelection
import com.clawsses.glasses.input.ModelPickerNavigation
import com.clawsses.glasses.protocol.PhoneHudMessage
import com.clawsses.glasses.state.HudStateEvent
import com.clawsses.glasses.state.HudStateReducer
import com.clawsses.glasses.ui.AgentPickerInfo
import com.clawsses.glasses.ui.ChatHudState
import com.clawsses.glasses.ui.HudCardActionDisplay
import com.clawsses.glasses.ui.HudCardDisplay
import com.clawsses.glasses.ui.LiveCaptionDisplay
import com.clawsses.glasses.ui.ModelPickerInfo
import com.clawsses.glasses.ui.SessionPickerInfo
import com.clawsses.shared.PeerProtocol

internal data class HudPhoneMessageEffectContext(
    val sessionPickerRequested: Boolean,
    val modelPickerRequested: Boolean,
    val agentPickerRequested: Boolean,
    val pendingModelPageSelection: ModelPageSelection,
)

internal sealed interface HudPhoneMessageEffect {
    data object RuntimeOwned : HudPhoneMessageEffect

    data class Apply(
        val state: ChatHudState,
        val sessionNextOffset: Int? = null,
        val sessionOffsetChanged: Boolean = false,
        val sessionRequestCompleted: Boolean = false,
        val modelRequestCompleted: Boolean = false,
        val agentRequestCompleted: Boolean = false,
        val resetModelPageSelection: Boolean = false,
        val scheduleCardExpiry: Boolean = false,
        val logMessage: String? = null,
    ) : HudPhoneMessageEffect
}

/** Plans deterministic HUD state effects while the Activity retains hardware and lifecycle work. */
internal class HudPhoneMessageEffectPlanner(
    private val newSessionKey: String,
    private val moreSessionsKey: String,
) {
    fun plan(
        current: ChatHudState,
        message: PhoneHudMessage,
        context: HudPhoneMessageEffectContext,
    ): HudPhoneMessageEffect = when (message) {
        is PhoneHudMessage.AgentPhase -> applyReduced(
            current,
            HudStateEvent.AgentPhaseChanged(message.phase),
        )
        is PhoneHudMessage.AgentProgress -> applyReduced(
            current,
            HudStateEvent.AgentProgressChanged(message.id, message.kind, message.label, message.state),
        )
        is PhoneHudMessage.Connection -> applyReduced(
            current,
            HudStateEvent.ConnectionChanged(message.connected, message.sessionId, message.sessionName),
        )
        is PhoneHudMessage.SessionList -> planSessionList(current, message)
        is PhoneHudMessage.SessionOperation -> planSessionOperation(current, message)
        is PhoneHudMessage.ModelPage -> planModelPage(current, message, context)
        is PhoneHudMessage.ModelOperation -> planModelOperation(current, message)
        is PhoneHudMessage.AgentList -> HudPhoneMessageEffect.Apply(
            state = HudStateReducer.reduce(
                current,
                HudStateEvent.AgentsLoaded(
                    agents = message.agents.map { AgentPickerInfo(it.id, it.name, it.model) },
                    currentAgentId = message.currentAgentId,
                    showPicker = context.agentPickerRequested,
                ),
            ).state,
            agentRequestCompleted = true,
        )
        is PhoneHudMessage.TtsState -> HudPhoneMessageEffect.Apply(
            current.copy(
                ttsEnabled = message.enabled,
                ttsPlaybackState = message.playbackState,
                ttsCanReplay = message.canReplay,
            ),
        )
        is PhoneHudMessage.RunState -> applyReduced(
            current,
            HudStateEvent.RunChanged(message.state, message.canAbort),
        )
        is PhoneHudMessage.TalkModeState -> applyReduced(
            current,
            HudStateEvent.TalkModeChanged(message.enabled, message.phase),
        )
        is PhoneHudMessage.HudCard -> planHudCard(current, message)
        is PhoneHudMessage.LiveCaption -> applyReduced(
            current,
            HudStateEvent.LiveCaptionChanged(
                message.enabled,
                LiveCaptionDisplay(
                    sourceText = message.sourceText,
                    translatedText = message.translatedText,
                    sourceLanguage = message.sourceLanguage,
                    targetLanguage = message.targetLanguage,
                    error = message.error,
                ),
            ),
        )
        is PhoneHudMessage.PeerState -> HudPhoneMessageEffect.Apply(
            state = current,
            logMessage = "Phone peer build=${message.versionCode}, protocol=${message.protocolVersion}, " +
                "compatibility=${PeerProtocol.compatibility(message.protocolVersion)}, " +
                "capabilities=${message.capabilities.sorted().joinToString()}",
        )
        is PhoneHudMessage.CompletedMessage,
        is PhoneHudMessage.History,
        is PhoneHudMessage.HistoryBegin,
        is PhoneHudMessage.HistoryChunk,
        is PhoneHudMessage.HistoryEnd,
        is PhoneHudMessage.Stream,
        is PhoneHudMessage.StreamEnd,
        is PhoneHudMessage.VoiceState,
        is PhoneHudMessage.VoiceResult,
        is PhoneHudMessage.PhotoResult,
        is PhoneHudMessage.RemovePhoto,
        is PhoneHudMessage.WakeSignal -> HudPhoneMessageEffect.RuntimeOwned
    }

    private fun applyReduced(
        current: ChatHudState,
        event: HudStateEvent,
    ) = HudPhoneMessageEffect.Apply(HudStateReducer.reduce(current, event).state)

    private fun planSessionList(
        current: ChatHudState,
        message: PhoneHudMessage.SessionList,
    ): HudPhoneMessageEffect {
        val sessions = listOf(SessionPickerInfo(newSessionKey, "+ New Session")) +
            message.sessions.map {
                SessionPickerInfo(it.key, it.name, it.kind, it.hasUnread, it.updatedAt)
            } + if (message.nextOffset != null) {
                listOf(SessionPickerInfo(moreSessionsKey, "More..."))
            } else {
                emptyList()
            }
        return HudPhoneMessageEffect.Apply(
            state = HudStateReducer.reduce(
                current,
                HudStateEvent.SessionsLoaded(sessions, message.currentSessionKey),
            ).state,
            sessionNextOffset = message.nextOffset,
            sessionOffsetChanged = true,
            sessionRequestCompleted = true,
        )
    }

    private fun planSessionOperation(
        current: ChatHudState,
        message: PhoneHudMessage.SessionOperation,
    ): HudPhoneMessageEffect {
        val state = when (message.state) {
            "loading" -> current.copy(
                showSessionPicker = true,
                isSessionOperationPending = true,
                sessionOperationMessage = if (message.operation == "create") {
                    "Creating session..."
                } else {
                    "Loading sessions..."
                },
                sessionOperationError = null,
            )
            "success" -> current.copy(
                showSessionPicker = message.operation != "create" && current.showSessionPicker,
                isSessionOperationPending = false,
                sessionOperationMessage = null,
                sessionOperationError = null,
            )
            "error" -> {
                val options = if (current.availableSessions.any { it.key == newSessionKey }) {
                    current.availableSessions
                } else {
                    listOf(SessionPickerInfo(newSessionKey, "+ New Session")) + current.availableSessions
                }
                current.copy(
                    showSessionPicker = true,
                    availableSessions = options,
                    selectedSessionIndex = current.selectedSessionIndex.coerceIn(options.indices),
                    isSessionOperationPending = false,
                    sessionOperationMessage = null,
                    sessionOperationError = message.error ?: "Session operation failed",
                )
            }
            else -> current
        }
        return HudPhoneMessageEffect.Apply(
            state = state,
            sessionRequestCompleted = message.state != "loading",
        )
    }

    private fun planModelPage(
        current: ChatHudState,
        message: PhoneHudMessage.ModelPage,
        context: HudPhoneMessageEffectContext,
    ): HudPhoneMessageEffect {
        val models = message.models.map {
            ModelPickerInfo(it.index, it.name, it.provider, it.available)
        }
        val currentIndexOnPage = models.indexOfFirst { it.index == message.currentIndex }
            .takeIf { it >= 0 }
        val selectedIndex = ModelPickerNavigation.initialIndex(
            itemCount = models.size,
            currentIndexOnPage = currentIndexOnPage,
            pageSelection = context.pendingModelPageSelection,
        )
        return HudPhoneMessageEffect.Apply(
            state = current.copy(
                showModelPicker = current.showModelPicker || context.modelPickerRequested,
                availableModels = models,
                modelCatalogId = message.catalogId,
                currentModelIndex = message.currentIndex,
                selectedModelIndex = selectedIndex,
                modelPageOffset = message.offset,
                modelNextOffset = message.nextOffset,
                modelPageIndex = message.pageIndex,
                modelPageCount = message.pageCount,
                isModelOperationPending = false,
                modelOperationMessage = null,
                modelOperationError = message.error,
            ),
            modelRequestCompleted = true,
            resetModelPageSelection = true,
        )
    }

    private fun planModelOperation(
        current: ChatHudState,
        message: PhoneHudMessage.ModelOperation,
    ): HudPhoneMessageEffect {
        val state = when (message.state) {
            "loading" -> current.copy(
                showModelPicker = true,
                isModelOperationPending = true,
                modelOperationMessage = "Changing model...",
                modelOperationError = null,
            )
            "success" -> current.copy(
                showModelPicker = false,
                currentModelIndex = message.currentIndex ?: current.currentModelIndex,
                isModelOperationPending = false,
                modelOperationMessage = null,
                modelOperationError = null,
            )
            "error" -> current.copy(
                showModelPicker = true,
                isModelOperationPending = false,
                modelOperationMessage = null,
                modelOperationError = message.error ?: "Could not change model",
            )
            else -> current
        }
        return HudPhoneMessageEffect.Apply(
            state = state,
            modelRequestCompleted = message.state != "loading",
        )
    }

    private fun planHudCard(
        current: ChatHudState,
        message: PhoneHudMessage.HudCard,
    ): HudPhoneMessageEffect {
        val card = HudCardDisplay(
            id = message.id,
            source = message.source,
            title = message.title,
            body = message.body,
            priority = message.priority,
            expiresAt = message.expiresAt,
            actions = message.actions.map { HudCardActionDisplay(it.id, it.label) },
        )
        if (card.id.isBlank() || card.body.isBlank()) {
            return HudPhoneMessageEffect.Apply(current)
        }
        return HudPhoneMessageEffect.Apply(
            state = current.copy(
                hudCards = (current.hudCards.filterNot { it.id == card.id } + card).takeLast(5),
                selectedHudCardActionIndex = 0,
            ),
            scheduleCardExpiry = true,
        )
    }
}
