package com.clawsses.glasses.ui

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

internal data class HudPageAnchor(
    val messageId: String,
    val characterOffset: Int,
)

internal data class HudMessageFragment(
    val message: DisplayMessage,
    val content: String,
    val startOffset: Int,
    val endOffset: Int,
    val showThumbnails: Boolean,
)

internal data class HudPage(
    val fragments: List<HudMessageFragment>,
    val showHistoryStart: Boolean = false,
) {
    val anchor: HudPageAnchor? get() = fragments.firstOrNull()?.let {
        HudPageAnchor(it.message.id, it.startOffset)
    }

    fun contains(anchor: HudPageAnchor): Boolean = fragments.any { fragment ->
        fragment.message.id == anchor.messageId && if (fragment.startOffset == fragment.endOffset) {
            anchor.characterOffset == fragment.startOffset
        } else {
            anchor.characterOffset >= fragment.startOffset && anchor.characterOffset < fragment.endOffset
        }
    }
}

/**
 * Builds fixed pages from the actual measured text lines used by the HUD.
 * Completed messages hit TextMeasurer's cache; only the growing tail changes
 * during streaming.
 */
internal fun paginateHudMessages(
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
    if (messages.isEmpty()) return emptyList()

    val pages = mutableListOf<HudPage>()
    var pageFragments = mutableListOf<HudMessageFragment>()
    var usedHeight = if (showHistoryStart) historyMarkerHeightPx else 0
    var currentPageShowsHistory = showHistoryStart

    fun finishPage() {
        if (pageFragments.isNotEmpty() || currentPageShowsHistory) {
            pages += HudPage(pageFragments.toList(), currentPageShowsHistory)
        }
        pageFragments = mutableListOf()
        usedHeight = 0
        currentPageShowsHistory = false
    }

    messages.forEach { message ->
        val measuredText = message.content.ifEmpty { " " }
        val outerPadding = if (message.role == "user") userOuterPaddingPx else assistantOuterPaddingPx
        val textWidth = (pageWidthPx - outerPadding - horizontalInnerPaddingPx).coerceAtLeast(1)
        val layout = textMeasurer.measure(
            text = measuredText,
            style = textStyle,
            constraints = Constraints(maxWidth = textWidth),
        )
        val lineCount = layout.lineCount.coerceAtLeast(1)
        var startLine = 0

        while (startLine < lineCount) {
            val firstFragment = startLine == 0
            val thumbnailSpace = if (firstFragment && message.thumbnails.isNotEmpty()) thumbnailHeightPx else 0
            val spacing = if (pageFragments.isNotEmpty()) messageSpacingPx else 0
            val fixedHeight = spacing + verticalInnerPaddingPx + thumbnailSpace
            val availableTextHeight = pageHeightPx - usedHeight - fixedHeight

            var endLine = startLine
            while (endLine < lineCount) {
                val lineHeight = layout.getLineBottom(endLine) - layout.getLineTop(startLine)
                if (lineHeight > availableTextHeight && endLine > startLine) break
                if (lineHeight > availableTextHeight && pageFragments.isNotEmpty()) break
                endLine++
            }

            if (endLine == startLine && usedHeight > 0) {
                finishPage()
                continue
            }
            if (endLine == startLine) endLine = startLine + 1

            val startOffset = if (message.content.isEmpty()) 0 else layout.getLineStart(startLine)
            val endOffset = if (message.content.isEmpty()) {
                0
            } else {
                layout.getLineEnd(endLine - 1, visibleEnd = false).coerceIn(startOffset, message.content.length)
            }
            val content = if (message.content.isEmpty()) "" else message.content.substring(startOffset, endOffset)
            val textHeight = (layout.getLineBottom(endLine - 1) - layout.getLineTop(startLine)).toInt()

            pageFragments += HudMessageFragment(
                message = message,
                content = content,
                startOffset = startOffset,
                endOffset = endOffset,
                showThumbnails = firstFragment && message.thumbnails.isNotEmpty(),
            )
            usedHeight += fixedHeight + textHeight
            startLine = endLine

            if (startLine < lineCount) finishPage()
        }
    }

    finishPage()
    return pages.ifEmpty { listOf(HudPage(emptyList(), showHistoryStart)) }
}

internal fun findHudPageForAnchor(pages: List<HudPage>, anchor: HudPageAnchor?): Int? {
    if (anchor == null) return null
    return pages.indexOfFirst { it.contains(anchor) }.takeIf { it >= 0 }
}
