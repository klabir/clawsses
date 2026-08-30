package com.clawsses.glasses.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class HudScreenContractTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val telemetry = MutableStateFlow(HudTelemetry(currentTime = "12:34"))
    private val streaming = MutableStateFlow<HudStreamingSnapshot?>(null)

    @Test
    fun disconnectedHudExposesConnectionAndFocusState() {
        setHud(ChatHudState())

        composeTestRule.onNodeWithText("disconnected").assertIsDisplayed()
        composeTestRule.onNodeWithText("SCROLL").assertIsDisplayed()
    }

    @Test
    fun sessionOverlayExposesPendingStateAndError() {
        setHud(
            ChatHudState(
                showSessionPicker = true,
                isSessionOperationPending = true,
                sessionOperationMessage = "Loading sessions...",
                sessionOperationError = "Gateway unavailable",
            ),
        )

        composeTestRule.onNodeWithText("SELECT SESSION").assertIsDisplayed()
        composeTestRule.onNodeWithText("Loading sessions...").assertIsDisplayed()
        composeTestRule.onNodeWithText("Gateway unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithText("Please wait  2×TAP Cancel").assertIsDisplayed()
    }

    @Test
    fun modelOverlayExposesCatalogItems() {
        setHud(
            ChatHudState(
                showModelPicker = true,
                availableModels = listOf(
                    ModelPickerInfo(0, "Codex", "openai", available = true),
                ),
                currentModelIndex = 0,
            ),
        )
        composeTestRule.onNodeWithText("SELECT MODEL").assertIsDisplayed()
        composeTestRule.onNodeWithText("Codex").assertIsDisplayed()
        composeTestRule.onNodeWithText("● CURRENT").assertIsDisplayed()
    }

    @Test
    fun agentOverlayExposesCatalogItems() {
        setHud(
            ChatHudState(
                showAgentPicker = true,
                availableAgents = listOf(AgentPickerInfo("main", "Bugl", "gpt-5")),
                currentAgentId = "main",
            ),
        )
        composeTestRule.onNodeWithText("SELECT AGENT").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bugl").assertIsDisplayed()
    }

    @Test
    fun stagedInputAndTelemetryRemainVisibleAfterSurfaceSplit() {
        setHud(
            ChatHudState(
                inputText = "Draft",
                stagingText = "Draft",
                showInputStaging = true,
                focusedArea = ChatFocusArea.INPUT,
            ),
        )

        composeTestRule.onNodeWithText("Draft").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send").assertIsDisplayed()
        composeTestRule.onNodeWithText("12:34").assertIsDisplayed()
    }

    private fun setHud(state: ChatHudState) {
        composeTestRule.setContent {
            HudScreen(
                state = state.toHudUiState(),
                telemetry = telemetry,
                streamingMessage = streaming,
            )
        }
    }
}
