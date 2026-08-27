package com.clawsses.phone.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clawsses.phone.openclaw.OpenClawClient

/** Isolates catalog updates from the rest of the main screen composition. */
@Composable
internal fun MainCatalogControls(openClawClient: OpenClawClient) {
    val sessions by openClawClient.sessionList.collectAsStateWithLifecycle()
    val agents by openClawClient.agentList.collectAsStateWithLifecycle()
    val models by openClawClient.modelList.collectAsStateWithLifecycle()
    val currentModelRef by openClawClient.currentModelRef.collectAsStateWithLifecycle()
    val selectingModel by openClawClient.isSelectingModel.collectAsStateWithLifecycle()
    val modelSelectionError by openClawClient.modelSelectionError.collectAsStateWithLifecycle()
    val currentSessionKey by openClawClient.currentSessionKey.collectAsStateWithLifecycle()
    val unreadSessions by openClawClient.unreadSessions.collectAsStateWithLifecycle()
    var showSessions by remember { mutableStateOf(false) }
    var showAgents by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }

    SessionSelector(
        sessions = sessions,
        currentSessionKey = currentSessionKey,
        unreadSessionKeys = unreadSessions,
        expanded = showSessions,
        onToggle = {
            if (!showSessions) openClawClient.requestSessions()
            showSessions = !showSessions
        },
        onSelect = { session ->
            showSessions = false
            openClawClient.switchSession(session.key)
        },
        onDismiss = { showSessions = false },
    )
    AgentSelector(
        agents = agents,
        currentAgentId = openClawClient.agentIdFromSessionKey(currentSessionKey),
        expanded = showAgents,
        onToggle = {
            if (!showAgents) openClawClient.requestAgents()
            showAgents = !showAgents
        },
        onSelect = { agent ->
            showAgents = false
            openClawClient.switchAgent(agent.id, agent.name)
        },
        onDismiss = { showAgents = false },
    )
    ModelSelector(
        models = models,
        currentModelRef = currentModelRef,
        expanded = showModels,
        selecting = selectingModel,
        error = modelSelectionError,
        onToggle = {
            if (!showModels) openClawClient.requestModels()
            showModels = !showModels
        },
        onSelect = { model ->
            showModels = false
            openClawClient.selectModel(model)
        },
        onDismiss = { showModels = false },
    )
}
