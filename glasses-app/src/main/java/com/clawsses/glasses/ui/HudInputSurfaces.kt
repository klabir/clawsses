package com.clawsses.glasses.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clawsses.glasses.media.ThumbnailBitmapCache
import com.clawsses.glasses.media.ThumbnailHandle
import kotlinx.coroutines.flow.StateFlow

private val greenThumbnailFilter = ColorFilter.colorMatrix(
    androidx.compose.ui.graphics.ColorMatrix(
        floatArrayOf(
            0f, 0f, 0f, 0f, 0f,
            0.3f, 0.59f, 0.11f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

@Composable
internal fun PhotoThumbnailRow(
    thumbnails: List<ThumbnailHandle>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        thumbnails.forEach { thumbnail ->
            val bitmap = remember(thumbnail) { ThumbnailBitmapCache.resolve(thumbnail) }
                ?: return@forEach
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(width = 24.dp, height = 18.dp)
                    .border(1.dp, HudColors.green.copy(alpha = 0.5f), RoundedCornerShape(1.dp)),
                contentScale = ContentScale.Crop,
                colorFilter = greenThumbnailFilter,
            )
        }
    }
}

/** Combined staged text/photo actions shown below the HUD chat content. */
@Composable
internal fun InputStagingArea(
    text: String,
    showText: Boolean,
    photos: List<ThumbnailHandle>,
    selectedIndex: Int,
    isFocused: Boolean,
    isProcessing: Boolean,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val commandFontSize = 8.sp
    val photoCount = photos.size
    val hasContent = text.isNotEmpty() || photos.isNotEmpty()
    val cursorVisible = if (isProcessing) {
        val infiniteTransition = rememberInfiniteTransition(label = "processingCursor")
        val cursorAlpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = tween(500)),
            label = "blink",
        )
        cursorAlpha > 0.5f
    } else {
        false
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        if (showText || isProcessing) {
            val borderColor = if (isProcessing) {
                HudColors.cyan.copy(alpha = 0.6f)
            } else if (isFocused) {
                HudColors.yellow.copy(alpha = 0.6f)
            } else {
                HudColors.dimText.copy(alpha = 0.4f)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isProcessing) HudColors.cyan.copy(alpha = 0.05f)
                        else HudColors.green.copy(alpha = 0.08f),
                        RoundedCornerShape(4.dp),
                    )
                    .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .heightIn(min = 20.dp, max = 60.dp),
            ) {
                val displayText = if (isProcessing) {
                    val cursor = if (cursorVisible) "\u2588" else " "
                    if (text.isNotEmpty()) "$text $cursor" else cursor
                } else {
                    text.ifEmpty { "..." }
                }
                val textColor = if (isProcessing && text.isEmpty()) {
                    HudColors.cyan
                } else if (text.isEmpty()) {
                    HudColors.dimText
                } else {
                    HudColors.primaryText
                }
                Text(
                    text = displayText,
                    color = textColor,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    lineHeight = fontSize,
                    letterSpacing = 0.sp,
                    softWrap = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            photos.forEachIndexed { index, thumbnail ->
                val bitmap = remember(thumbnail) { ThumbnailBitmapCache.resolve(thumbnail) }
                    ?: return@forEachIndexed
                val isSelected = index == selectedIndex && isFocused
                Box(modifier = Modifier.padding(end = 4.dp)) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Photo ${index + 1}",
                        modifier = Modifier
                            .size(width = 36.dp, height = 27.dp)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) HudColors.green else HudColors.dimText,
                                shape = RoundedCornerShape(2.dp),
                            ),
                        contentScale = ContentScale.Crop,
                        colorFilter = greenThumbnailFilter,
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 27.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "\u2715",
                                color = HudColors.green,
                                fontSize = 12.sp,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            if (hasContent) {
                Spacer(modifier = Modifier.weight(1f))
                StagingAction(
                    item = InputActionItem.CLEAR,
                    selected = selectedIndex == photoCount && isFocused,
                    commandFontSize = commandFontSize,
                    fontFamily = fontFamily,
                )
                Spacer(modifier = Modifier.width(4.dp))
                StagingAction(
                    item = InputActionItem.SEND,
                    selected = selectedIndex == photoCount + 1 && isFocused,
                    commandFontSize = commandFontSize,
                    fontFamily = fontFamily,
                )
            }
        }
    }
}

@Composable
private fun StagingAction(
    item: InputActionItem,
    selected: Boolean,
    commandFontSize: TextUnit,
    fontFamily: FontFamily,
) {
    Box(
        modifier = Modifier
            .background(
                if (selected) HudColors.green.copy(alpha = 0.3f) else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) HudColors.green else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.icon,
                color = if (selected) HudColors.green else HudColors.primaryText,
                fontSize = (commandFontSize.value + 2).sp,
                fontFamily = fontFamily,
            )
            Text(
                text = item.label,
                color = if (selected) HudColors.green else HudColors.dimText,
                fontSize = commandFontSize,
                fontFamily = fontFamily,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
internal fun ChatMenuBar(
    selectedIndex: Int,
    isFocused: Boolean,
    hudPosition: HudPosition,
    telemetry: StateFlow<HudTelemetry>,
    fontFamily: FontFamily,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val telemetryState by telemetry.collectAsStateWithLifecycle()
    val commandFontSize = 8.sp
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier.fillMaxWidth().alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuBarItem.entries.forEachIndexed { index, item ->
                val selected = index == selectedIndex && isFocused
                val displayIcon = if (item == MenuBarItem.SIZE) {
                    when (hudPosition) {
                        HudPosition.FULL -> "\u2584"
                        HudPosition.BOTTOM_HALF -> "\u2580"
                        HudPosition.TOP_HALF -> "\u2588"
                    }
                } else {
                    item.icon
                }
                Box(
                    modifier = Modifier
                        .background(
                            if (selected) HudColors.green.copy(alpha = 0.3f) else Color.Transparent,
                            RoundedCornerShape(4.dp),
                        )
                        .border(
                            width = if (selected) 1.dp else 0.dp,
                            color = if (selected) HudColors.green else Color.Transparent,
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = displayIcon,
                            color = if (selected) HudColors.green else HudColors.primaryText,
                            fontSize = (commandFontSize.value + 2).sp,
                            fontFamily = fontFamily,
                        )
                        Text(
                            text = item.label,
                            color = if (selected) HudColors.green else HudColors.dimText,
                            fontSize = commandFontSize,
                            fontFamily = fontFamily,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        if (telemetryState.currentTime.isNotEmpty()) {
            Text(
                text = telemetryState.currentTime,
                color = HudColors.dimText,
                fontSize = commandFontSize,
                fontFamily = fontFamily,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        telemetryState.batteryLevel?.let { level ->
            Text(
                text = "${if (telemetryState.batteryCharging) "\u26A1" else "\uD83D\uDD0B"}$level%",
                color = if (level <= 15) HudColors.error else HudColors.dimText,
                fontSize = commandFontSize,
                fontFamily = fontFamily,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
