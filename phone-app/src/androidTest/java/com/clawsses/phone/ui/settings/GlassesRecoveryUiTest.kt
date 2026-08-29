package com.clawsses.phone.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clawsses.phone.glasses.GlassesConnectionManager
import com.clawsses.phone.glasses.GlassesRecoverySnapshot
import com.clawsses.phone.glasses.GlassesRecoveryStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GlassesRecoveryUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun deepSleepExplainsThatPairingIsIntactAndRetriesOnce() {
        var retries = 0
        composeTestRule.setContent {
            GlassesSection(
                state = GlassesConnectionManager.ConnectionState.Disconnected,
                discoveredDevices = emptyList(),
                wifiP2PConnected = false,
                debugModeEnabled = false,
                onStartScanning = {},
                onStopScanning = {},
                onConnectDevice = {},
                onDisconnectGlasses = {},
                onInitWifiP2P = {},
                onClearSn = {},
                onCancelReconnect = {},
                hasCachedSn = true,
                cachedSn = null,
                cachedDeviceName = "Rokid Glasses",
                recoveryState = GlassesRecoverySnapshot(
                    status = GlassesRecoveryStatus.DEEP_SLEEP_SUSPECTED,
                    reconnectAttempt = 5,
                    deepSleepDetections = 1,
                    reconnectTimeouts = 5,
                ),
                onWakeProbe = { retries += 1 },
            )
        }

        composeTestRule.onNodeWithText("Glasses may be deeply asleep").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Android pairing is still intact. Press the glasses button 3× until the blue " +
                "LED blinks, then retry. Do not clear pairing.",
        ).assertIsDisplayed()

        composeTestRule.onNodeWithText("I pressed 3× — retry").performClick()

        assertEquals(1, retries)
    }
}
