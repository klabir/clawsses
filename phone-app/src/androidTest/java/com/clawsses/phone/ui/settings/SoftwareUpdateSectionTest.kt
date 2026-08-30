package com.clawsses.phone.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clawsses.phone.glasses.ApkInstaller
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SoftwareUpdateSectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun pendingVerificationOffersVerifyAndReinstall() {
        var verifies = 0
        var installs = 0
        setContent(
            state = ApkInstaller.InstallState.InstalledPendingVerification(
                expectedBuild = 114,
                message = "Wake the glasses and verify the live peer build.",
            ),
            onInstall = { installs += 1 },
            onVerify = { verifies += 1 },
        )

        composeTestRule.onNodeWithText("Installed — verification pending").assertIsDisplayed()
        composeTestRule.onNodeWithText("Wake the glasses and verify the live peer build.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Verify installed build").performClick()
        composeTestRule.onNodeWithText("Install again").performClick()

        assertEquals(1, verifies)
        assertEquals(1, installs)
    }

    @Test
    fun successfulInstallShowsVerifiedMessageAndNoRetryAction() {
        setContent(
            ApkInstaller.InstallState.Success("Glasses Build 114 installed and verified."),
        )

        composeTestRule.onNodeWithText("Installation Complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Glasses Build 114 installed and verified.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Try Again").assertDoesNotExist()
    }

    @Test
    fun retryableFailureInvokesOneRetry() {
        var installs = 0
        setContent(
            state = ApkInstaller.InstallState.Error("Peer verification timed out."),
            onInstall = { installs += 1 },
        )

        composeTestRule.onNodeWithText("Installation Failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Try Again").performClick()

        assertEquals(1, installs)
    }

    @Test
    fun ownershipWaitCanBeCancelled() {
        var cancels = 0
        setContent(
            state = ApkInstaller.InstallState.AwaitingPeerOwnership(expectedBuild = 114),
            onCancel = { cancels += 1 },
        )

        composeTestRule.onNodeWithText("Expected Build 114").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, cancels)
    }

    private fun setContent(
        state: ApkInstaller.InstallState,
        onInstall: () -> Unit = {},
        onVerify: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            SoftwareUpdateSection(
                installState = state,
                onInstall = onInstall,
                onVerifyInstall = onVerify,
                onCancel = onCancel,
            )
        }
    }
}
