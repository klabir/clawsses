
package com.clawsses.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HudCardOverlay(
    card: HudCardDisplay,
    selectedActionIndex: Int,
    queuedCount: Int,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .border(1.dp, if (card.priority == "high") HudColors.cyan else HudColors.green, RoundedCornerShape(12.dp))
                .padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(card.source.uppercase(), color = HudColors.cyan, fontSize = 11.sp, fontFamily = fontFamily)
                if (queuedCount > 1) {
                    Text("+${queuedCount - 1}", color = HudColors.dimText, fontSize = 10.sp, fontFamily = fontFamily)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(card.title, color = HudColors.green, fontSize = 17.sp, fontFamily = fontFamily, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(card.body, color = HudColors.primaryText, fontSize = 13.sp, fontFamily = fontFamily, maxLines = 9)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                card.actions.forEachIndexed { index, action ->
                    Text(
                        text = if (index == selectedActionIndex) "[${action.label}]" else action.label,
                        color = if (index == selectedActionIndex) HudColors.green else HudColors.dimText,
                        fontSize = 12.sp,
                        fontFamily = fontFamily,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("SWIPE Action  TAP Select  2×TAP Dismiss", color = HudColors.dimText, fontSize = 9.sp, fontFamily = fontFamily)
        }
    }
}

@Composable
internal fun LiveCaptionOverlay(
    caption: LiveCaptionDisplay?,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 24.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(10.dp))
                .border(1.dp, HudColors.cyan.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                .padding(14.dp),
        ) {
            Text("LIVE CAPTIONS", color = HudColors.cyan, fontSize = 10.sp, fontFamily = fontFamily, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                caption?.sourceText?.takeIf { it.isNotBlank() } ?: "Listening…",
                color = HudColors.primaryText,
                fontSize = 15.sp,
                fontFamily = fontFamily,
            )
            caption?.translatedText?.takeIf { it.isNotBlank() }?.let { translated ->
                Spacer(Modifier.height(8.dp))
                Text(translated, color = HudColors.green, fontSize = 15.sp, fontFamily = fontFamily)
            }
            caption?.error?.let { error ->
                Spacer(Modifier.height(6.dp))
                Text(error, color = Color(0xFFFF8A80), fontSize = 10.sp, fontFamily = fontFamily)
            }
            Spacer(Modifier.height(8.dp))
            Text("2×TAP to stop", color = HudColors.dimText, fontSize = 9.sp, fontFamily = fontFamily)
        }
    }
}

// ============================================================================
// MORE MENU OVERLAY
// ============================================================================

@Composable
internal fun MoreMenuOverlay(
    selectedIndex: Int,
    currentDisplaySize: HudDisplaySize,
    ttsEnabled: Boolean,
    ttsPlaybackState: String,
    ttsCanReplay: Boolean,
    runState: String,
    runCanAbort: Boolean,
    talkModeEnabled: Boolean,
    liveCaptionEnabled: Boolean,
    showAgentSelector: Boolean,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val items = visibleMoreMenuItems(showAgentSelector)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "MORE",
                color = HudColors.green,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEachIndexed { itemIndex, item ->
                    val isSelected = itemIndex == selectedIndex
                    val isActive = when (item) {
                        MoreMenuItem.VOICE -> ttsEnabled
                        MoreMenuItem.TTS_STOP -> ttsPlaybackState == "playing" || ttsPlaybackState == "synthesizing"
                        MoreMenuItem.TTS_REPLAY -> ttsCanReplay
                        MoreMenuItem.STOP_RUN -> runCanAbort || runState == "aborting"
                        MoreMenuItem.TALK -> talkModeEnabled
                        MoreMenuItem.CAPTIONS -> liveCaptionEnabled
                        else -> item.displaySize == currentDisplaySize
                    }

                    // Dynamic label for VOICE item
                    val displayLabel = when (item) {
                        MoreMenuItem.VOICE -> if (ttsEnabled) "Voice On" else "Voice Off"
                        MoreMenuItem.TTS_STOP -> "Stop Voice"
                        MoreMenuItem.TTS_REPLAY -> "Replay Voice"
                        MoreMenuItem.STOP_RUN -> if (runState == "aborting") "Stopping Run" else "Stop Run"
                        MoreMenuItem.TALK -> if (talkModeEnabled) "Talk On" else "Talk Off"
                        MoreMenuItem.CAPTIONS -> if (liveCaptionEnabled) "Captions On" else "Captions Off"
                        else -> item.label
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Selection indicator
                        Text(
                            text = "\u25B6",
                            color = if (isSelected) HudColors.green else Color.Transparent,
                            fontSize = 14.sp,
                            fontFamily = fontFamily
                        )
                        // Active checkmark for font size items and voice toggle
                        Text(
                            text = if (isActive) "\u2713" else " ",
                            color = HudColors.green,
                            fontSize = 14.sp,
                            fontFamily = fontFamily
                        )
                        // Icon and label rendered at the item's own font size for font entries
                        val itemFontSize = item.displaySize?.fontSizeSp?.sp ?: 14.sp
                        Text(
                            text = item.icon,
                            color = if (isSelected) HudColors.cyan else HudColors.primaryText,
                            fontSize = itemFontSize,
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = displayLabel,
                            color = if (isSelected) HudColors.green else HudColors.dimText,
                            fontSize = itemFontSize,
                            fontFamily = fontFamily
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "\u2191\u2193 Navigate  TAP Select  2\u00D7TAP Cancel",
                color = HudColors.dimText,
                fontSize = 10.sp,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// SLASH COMMAND OVERLAY
// ============================================================================

@Composable
internal fun SlashCommandOverlay(
    selectedIndex: Int,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Keep selected item visible
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem(selectedIndex)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = "SLASH COMMANDS",
                color = HudColors.cyan,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(SLASH_COMMANDS) { index, item ->
                    val isSelected = index == selectedIndex

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) HudColors.green.copy(alpha = 0.3f)
                                else Color.Transparent
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSelected) "\u25B6" else " ",
                            color = HudColors.green,
                            fontSize = 12.sp,
                            fontFamily = fontFamily
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.command,
                            color = if (isSelected) HudColors.green else HudColors.primaryText,
                            fontSize = 12.sp,
                            fontFamily = fontFamily,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(80.dp)
                        )
                        Text(
                            text = item.description,
                            color = HudColors.dimText,
                            fontSize = 10.sp,
                            fontFamily = fontFamily,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "\u2191\u2193 Navigate  TAP Select  2\u00D7TAP Cancel",
                color = HudColors.dimText,
                fontSize = 10.sp,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// EXIT CONFIRMATION OVERLAY
// ============================================================================

@Composable
internal fun ExitConfirmOverlay(
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "EXIT",
                color = HudColors.error,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "2\u00D7TAP again to exit",
                color = HudColors.primaryText,
                fontSize = 14.sp,
                fontFamily = fontFamily,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Any other input to continue",
                color = HudColors.dimText,
                fontSize = 12.sp,
                fontFamily = fontFamily,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ============================================================================
// HUD COLORS
// ============================================================================
