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

// ============================================================================
// SESSION PICKER OVERLAY
// ============================================================================

@Composable
internal fun SessionPickerOverlay(
    sessions: List<SessionPickerInfo>,
    currentSessionKey: String?,
    selectedIndex: Int,
    isPending: Boolean,
    statusMessage: String?,
    errorMessage: String?,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Keep selected item visible
    LaunchedEffect(selectedIndex) {
        if (sessions.isNotEmpty()) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxHeight()
                .padding(24.dp)
        ) {
            Text(
                text = "SELECT SESSION",
                color = HudColors.cyan,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            statusMessage?.let { message ->
                Text(
                    text = message,
                    color = if (isPending) HudColors.cyan else HudColors.dimText,
                    fontSize = 12.sp,
                    fontFamily = fontFamily
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = HudColors.error,
                    fontSize = 12.sp,
                    fontFamily = fontFamily,
                    maxLines = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (sessions.isEmpty()) {
                Text(
                    text = "No sessions available",
                    color = HudColors.dimText,
                    fontSize = 14.sp,
                    fontFamily = fontFamily
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    itemsIndexed(sessions) { index, session ->
                        val isSelected = index == selectedIndex
                        val isCurrent = session.key == currentSessionKey
                        val isNewSession = session.key == "__new_session__"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) HudColors.green.copy(alpha = 0.3f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (isSelected) "\u25B6" else " ",
                                    color = if (isNewSession) HudColors.cyan else HudColors.green,
                                    fontSize = 14.sp,
                                    fontFamily = fontFamily
                                )
                                Text(
                                    text = session.name,
                                    color = when {
                                        isNewSession && isSelected -> HudColors.cyan
                                        isNewSession -> HudColors.cyan
                                        isSelected -> HudColors.green
                                        else -> HudColors.primaryText
                                    },
                                    fontSize = 14.sp,
                                    fontFamily = fontFamily,
                                    fontWeight = if (isCurrent || isNewSession) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (session.updatedAt != null) {
                                    Text(
                                        text = formatRelativeTime(session.updatedAt),
                                        color = if (isSelected) Color.Black else HudColors.dimText,
                                        fontSize = 10.sp,
                                        fontFamily = fontFamily,
                                        maxLines = 1
                                    )
                                }
                                if (isCurrent) {
                                    Text(
                                        text = "\u25CF",
                                        color = HudColors.cyan,
                                        fontSize = 12.sp
                                    )
                                } else if (session.hasUnread) {
                                    Text(
                                        text = "\u25CF",
                                        color = HudColors.green,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isPending) {
                    "Please wait  2\u00D7TAP Cancel"
                } else {
                    "\u2191\u2193 Navigate  TAP Select  2\u00D7TAP Cancel"
                },
                color = HudColors.dimText,
                fontSize = 10.sp,
                fontFamily = fontFamily
            )
        }
    }
}

// ============================================================================
// MODEL / AGENT PICKER OVERLAYS
// ============================================================================

@Composable
internal fun ModelPickerOverlay(
    models: List<ModelPickerInfo>,
    currentModelIndex: Int?,
    selectedIndex: Int,
    pageIndex: Int,
    pageCount: Int,
    isPending: Boolean,
    statusMessage: String?,
    errorMessage: String?,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
) {
    val canMoveBackward = selectedIndex > 0 || pageIndex > 0
    val canMoveForward = selectedIndex < models.lastIndex || pageIndex < pageCount - 1

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxHeight()
                .padding(24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "SELECT MODEL",
                    color = HudColors.cyan,
                    fontSize = 16.sp,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${pageIndex + 1}/${pageCount.coerceAtLeast(1)}",
                    color = HudColors.dimText,
                    fontSize = 11.sp,
                    fontFamily = fontFamily,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (models.isEmpty()) {
                Text(
                    text = statusMessage ?: errorMessage ?: "No models available",
                    color = if (errorMessage != null) HudColors.error else HudColors.dimText,
                    fontSize = 13.sp,
                    fontFamily = fontFamily,
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    models.forEachIndexed { localIndex, model ->
                        val isSelected = localIndex == selectedIndex
                        val isCurrent = model.index == currentModelIndex
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 112.dp)
                                .background(
                                    if (isSelected) HudColors.green.copy(alpha = 0.3f)
                                    else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                )
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = if (isSelected) HudColors.green else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = if (isSelected) "\u25C6" else " ",
                                color = HudColors.green,
                                fontSize = 13.sp,
                                fontFamily = fontFamily,
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = model.name,
                                color = when {
                                    !model.available -> HudColors.dimText
                                    isSelected -> HudColors.green
                                    else -> HudColors.primaryText
                                },
                                fontSize = 13.sp,
                                fontFamily = fontFamily,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = if (model.available) model.provider else "unavailable",
                                color = HudColors.dimText,
                                fontSize = 9.sp,
                                fontFamily = fontFamily,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            if (isCurrent) {
                                Spacer(modifier = Modifier.height(5.dp))
                                Text(
                                    text = "\u25CF CURRENT",
                                    color = HudColors.cyan,
                                    fontSize = 9.sp,
                                    fontFamily = fontFamily,
                                    fontWeight = FontWeight.Bold,
                                )
                            } else {
                                Spacer(modifier = Modifier.height(14.dp))
                            }
                        }
                    }
                }
            }

            statusMessage?.takeIf { models.isNotEmpty() }?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(message, color = HudColors.cyan, fontSize = 10.sp, fontFamily = fontFamily)
            }
            errorMessage?.takeIf { models.isNotEmpty() }?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(error, color = HudColors.error, fontSize = 10.sp, fontFamily = fontFamily)
            }
            Spacer(modifier = Modifier.height(14.dp))
            if (isPending) {
                Text(
                    text = "Please wait  2\u00D7TAP Cancel",
                    color = HudColors.dimText,
                    fontSize = 10.sp,
                    fontFamily = fontFamily,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "\u2039 BACK",
                        color = if (canMoveBackward) HudColors.primaryText else HudColors.dimText,
                        fontSize = 10.sp,
                        fontFamily = fontFamily,
                        fontWeight = if (canMoveBackward) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        text = "SWIPE",
                        color = HudColors.dimText,
                        fontSize = 9.sp,
                        fontFamily = fontFamily,
                    )
                    Text(
                        text = "FORWARD \u203A",
                        color = if (canMoveForward) HudColors.primaryText else HudColors.dimText,
                        fontSize = 10.sp,
                        fontFamily = fontFamily,
                        fontWeight = if (canMoveForward) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "TAP Select   2\u00D7TAP Cancel",
                    color = HudColors.dimText,
                    fontSize = 9.sp,
                    fontFamily = fontFamily,
                )
            }
        }
    }
}

@Composable
internal fun AgentPickerOverlay(
    agents: List<AgentPickerInfo>,
    currentAgentId: String?,
    selectedIndex: Int,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedIndex) {
        if (agents.isNotEmpty()) listState.animateScrollToItem(selectedIndex)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxHeight()
                .padding(24.dp)
        ) {
            Text(
                text = "SELECT AGENT",
                color = HudColors.cyan,
                fontSize = 16.sp,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (agents.isEmpty()) {
                Text(
                    text = "No agents available",
                    color = HudColors.dimText,
                    fontSize = 14.sp,
                    fontFamily = fontFamily
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    itemsIndexed(agents) { index, agent ->
                        val isSelected = index == selectedIndex
                        val isCurrent = agent.id == currentAgentId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) HudColors.green.copy(alpha = 0.3f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (isSelected) "\u25B6" else " ",
                                    color = HudColors.green,
                                    fontSize = 14.sp,
                                    fontFamily = fontFamily
                                )
                                Column {
                                    Text(
                                        text = agent.name,
                                        color = if (isSelected) HudColors.green else HudColors.primaryText,
                                        fontSize = 14.sp,
                                        fontFamily = fontFamily,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1
                                    )
                                    agent.model?.takeIf { it.isNotBlank() }?.let { model ->
                                        Text(
                                            text = model,
                                            color = HudColors.dimText,
                                            fontSize = 10.sp,
                                            fontFamily = fontFamily,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                            if (isCurrent) {
                                Text(text = "\u25CF", color = HudColors.cyan, fontSize = 12.sp)
                            }
                        }
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

