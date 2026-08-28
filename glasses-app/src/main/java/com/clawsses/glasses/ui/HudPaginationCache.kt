package com.clawsses.glasses.ui

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle

internal data class HudPaginationReusePlan(
    val reusablePageCount: Int,
    val restartMessageIndex: Int,
    val unchanged: Boolean = false,
)

/**
 * Finds the earliest page that must be rebuilt after the message document changes.
 *
 * A page can contain the end of one message and the start of another. If the first
 * message on the rebuild page started on an earlier page, that earlier page must be
 * rebuilt too; otherwise the repeated message would be duplicated in the result.
 */
internal fun planHudPaginationReuse(
    previousMessages: List<DisplayMessage>,
    previousPages: List<HudPage>,
    messages: List<DisplayMessage>,
    layoutCompatible: Boolean,
): HudPaginationReusePlan {
    if (!layoutCompatible || previousPages.isEmpty()) {
        return HudPaginationReusePlan(0, 0)
    }

    val commonPrefixSize = previousMessages.indices
        .takeWhile { index -> index < messages.size && previousMessages[index] == messages[index] }
        .count()

    if (commonPrefixSize == previousMessages.size && commonPrefixSize == messages.size) {
        return HudPaginationReusePlan(previousPages.size, messages.size, unchanged = true)
    }
    if (commonPrefixSize == 0) return HudPaginationReusePlan(0, 0)

    val firstChangedId = previousMessages.getOrNull(commonPrefixSize)?.id
    var restartPageIndex = if (firstChangedId == null) {
        previousPages.lastIndex
    } else {
        previousPages.indexOfFirst { page ->
            page.fragments.any { fragment -> fragment.message.id == firstChangedId }
        }.takeIf { it >= 0 } ?: previousPages.lastIndex
    }

    while (restartPageIndex > 0) {
        val firstMessageId = previousPages[restartPageIndex]
            .fragments
            .firstOrNull()
            ?.message
            ?.id
            ?: return HudPaginationReusePlan(0, 0)
        val firstPageForMessage = previousPages.indexOfFirst { page ->
            page.fragments.any { fragment -> fragment.message.id == firstMessageId }
        }
        if (firstPageForMessage < 0 || firstPageForMessage == restartPageIndex) break
        restartPageIndex = firstPageForMessage
    }

    val restartMessageId = previousPages[restartPageIndex]
        .fragments
        .firstOrNull()
        ?.message
        ?.id
        ?: return HudPaginationReusePlan(0, 0)
    val restartMessageIndex = messages.indexOfFirst { it.id == restartMessageId }
    if (restartMessageIndex < 0) return HudPaginationReusePlan(0, 0)

    return HudPaginationReusePlan(
        reusablePageCount = restartPageIndex,
        restartMessageIndex = restartMessageIndex,
    )
}

private data class HudPaginationLayoutKey(
    val textStyle: TextStyle,
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val assistantOuterPaddingPx: Int,
    val userOuterPaddingPx: Int,
    val horizontalInnerPaddingPx: Int,
    val verticalInnerPaddingPx: Int,
    val messageSpacingPx: Int,
    val thumbnailHeightPx: Int,
    val historyMarkerHeightPx: Int,
    val showHistoryStart: Boolean,
)

/** Keeps completed prefix pages stable while only the live tail is remeasured. */
internal class HudPaginationCache {
    private var previousMessages: List<DisplayMessage> = emptyList()
    private var previousPages: List<HudPage> = emptyList()
    private var previousLayoutKey: HudPaginationLayoutKey? = null
    private var previousTextMeasurer: TextMeasurer? = null

    fun paginate(
        messages: List<DisplayMessage>,
        textMeasurer: TextMeasurer,
        textStyle: TextStyle,
        pageWidthPx: Int,
        pageHeightPx: Int,
        assistantOuterPaddingPx: Int,
        userOuterPaddingPx: Int,
        horizontalInnerPaddingPx: Int,
        verticalInnerPaddingPx: Int,
        messageSpacingPx: Int,
        thumbnailHeightPx: Int,
        historyMarkerHeightPx: Int,
        showHistoryStart: Boolean,
    ): List<HudPage> {
        val layoutKey = HudPaginationLayoutKey(
            textStyle = textStyle,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            assistantOuterPaddingPx = assistantOuterPaddingPx,
            userOuterPaddingPx = userOuterPaddingPx,
            horizontalInnerPaddingPx = horizontalInnerPaddingPx,
            verticalInnerPaddingPx = verticalInnerPaddingPx,
            messageSpacingPx = messageSpacingPx,
            thumbnailHeightPx = thumbnailHeightPx,
            historyMarkerHeightPx = historyMarkerHeightPx,
            showHistoryStart = showHistoryStart,
        )
        val reusePlan = planHudPaginationReuse(
            previousMessages = previousMessages,
            previousPages = previousPages,
            messages = messages,
            layoutCompatible = previousLayoutKey == layoutKey && previousTextMeasurer === textMeasurer,
        )
        if (reusePlan.unchanged) return previousPages

        val reusablePages = previousPages.take(reusePlan.reusablePageCount)
        val rebuiltPages = paginateHudMessages(
            messages = messages.drop(reusePlan.restartMessageIndex),
            textMeasurer = textMeasurer,
            textStyle = textStyle,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            assistantOuterPaddingPx = assistantOuterPaddingPx,
            userOuterPaddingPx = userOuterPaddingPx,
            horizontalInnerPaddingPx = horizontalInnerPaddingPx,
            verticalInnerPaddingPx = verticalInnerPaddingPx,
            messageSpacingPx = messageSpacingPx,
            thumbnailHeightPx = thumbnailHeightPx,
            historyMarkerHeightPx = historyMarkerHeightPx,
            showHistoryStart = showHistoryStart && reusablePages.isEmpty(),
        )
        val pages = reusablePages + rebuiltPages

        previousMessages = messages.toList()
        previousPages = pages
        previousLayoutKey = layoutKey
        previousTextMeasurer = textMeasurer
        return pages
    }
}
