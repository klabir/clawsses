package com.clawsses.phone.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clawsses.phone.openclaw.OpenClawClient
import org.junit.Rule
import org.junit.Test

class ServerSectionTokenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(token: String = "my-secret-token") {
        composeTestRule.setContent {
            ServerSection(
                initialHost = "localhost",
                initialPort = "18789",
                initialToken = token,
                connectionState = OpenClawClient.ConnectionState.Disconnected,
                onApply = { _, _, _ -> },
            )
        }
    }

    @Test
    fun tokenIsMaskedByDefault() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Show token")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("my-secret-token")
            .assertDoesNotExist()
    }

    @Test
    fun tappingToggleRevealsToken() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Show token")
            .performClick()

        composeTestRule.onNodeWithContentDescription("Hide token")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("my-secret-token")
            .assertIsDisplayed()
    }

    @Test
    fun tappingToggleTwiceRehidesToken() {
        setContent()

        composeTestRule.onNodeWithContentDescription("Show token")
            .performClick()
        composeTestRule.onNodeWithContentDescription("Hide token")
            .performClick()

        composeTestRule.onNodeWithContentDescription("Show token")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("my-secret-token")
            .assertDoesNotExist()
    }
}
