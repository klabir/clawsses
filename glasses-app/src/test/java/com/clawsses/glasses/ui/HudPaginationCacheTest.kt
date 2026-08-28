package com.clawsses.glasses.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudPaginationCacheTest {
    @Test
    fun unchangedDocumentReusesEveryPage() {
        val messages = listOf(message("one"), message("two"))
        val pages = listOf(page(messages[0]), page(messages[1]))

        val plan = planHudPaginationReuse(messages, pages, messages, layoutCompatible = true)

        assertTrue(plan.unchanged)
        assertEquals(2, plan.reusablePageCount)
        assertEquals(2, plan.restartMessageIndex)
    }

    @Test
    fun growingTailOnlyRebuildsPagesContainingTail() {
        val first = message("first")
        val oldTail = message("tail", "partial")
        val newTail = oldTail.copy(content = "partial response")
        val pages = listOf(page(first), page(oldTail))

        val plan = planHudPaginationReuse(
            previousMessages = listOf(first, oldTail),
            previousPages = pages,
            messages = listOf(first, newTail),
            layoutCompatible = true,
        )

        assertFalse(plan.unchanged)
        assertEquals(0, plan.reusablePageCount)
        assertEquals(0, plan.restartMessageIndex)
    }

    @Test
    fun veryLongAppendOnlyTailRebuildsOnlyFinalTwoPages() {
        val prefix = message("prefix")
        val oldTail = message("tail", "x".repeat(10_000))
        val newTail = oldTail.copy(content = oldTail.content + " more")
        val tailPages = (0 until 100).map { pageIndex ->
            HudPage(
                listOf(
                    HudMessageFragment(
                        message = oldTail,
                        content = "x".repeat(100),
                        startOffset = pageIndex * 100,
                        endOffset = (pageIndex + 1) * 100,
                        showThumbnails = false,
                    )
                )
            )
        }
        val pages = listOf(page(prefix)) + tailPages

        val plan = planHudPaginationReuse(
            previousMessages = listOf(prefix, oldTail),
            previousPages = pages,
            messages = listOf(prefix, newTail),
            layoutCompatible = true,
        )

        assertEquals(99, plan.reusablePageCount)
        assertEquals(1, plan.restartMessageIndex)
        assertEquals(9_800, plan.restartCharacterOffset)
    }

    @Test
    fun singleLongStreamingMessageStillUsesBoundedTailRebuild() {
        val oldTail = message("tail", "x".repeat(10_000))
        val pages = (0 until 100).map { pageIndex ->
            HudPage(
                listOf(
                    HudMessageFragment(
                        message = oldTail,
                        content = "x".repeat(100),
                        startOffset = pageIndex * 100,
                        endOffset = (pageIndex + 1) * 100,
                        showThumbnails = false,
                    )
                )
            )
        }

        val plan = planHudPaginationReuse(
            previousMessages = listOf(oldTail),
            previousPages = pages,
            messages = listOf(oldTail.copy(content = oldTail.content + " more")),
            layoutCompatible = true,
        )

        assertEquals(98, plan.reusablePageCount)
        assertEquals(0, plan.restartMessageIndex)
        assertEquals(9_800, plan.restartCharacterOffset)
    }

    @Test
    fun appendedMessageRebuildsPreviousLastPageToUseRemainingSpace() {
        val first = message("first")
        val second = message("second")
        val appended = message("third")

        val plan = planHudPaginationReuse(
            previousMessages = listOf(first, second),
            previousPages = listOf(page(first), page(second)),
            messages = listOf(first, second, appended),
            layoutCompatible = true,
        )

        assertEquals(1, plan.reusablePageCount)
        assertEquals(1, plan.restartMessageIndex)
    }

    @Test
    fun overlappingMessageRollsReuseBackToItsFirstPage() {
        val longMessage = message("long")
        val oldTail = message("tail", "partial")
        val newTail = oldTail.copy(content = "complete")
        val pages = listOf(
            page(longMessage),
            HudPage(
                fragments = listOf(fragment(longMessage), fragment(oldTail)),
            ),
        )

        val plan = planHudPaginationReuse(
            previousMessages = listOf(longMessage, oldTail),
            previousPages = pages,
            messages = listOf(longMessage, newTail),
            layoutCompatible = true,
        )

        assertEquals(0, plan.reusablePageCount)
        assertEquals(0, plan.restartMessageIndex)
    }

    @Test
    fun changedLayoutOrPrependedHistoryForcesFullPagination() {
        val first = message("first")
        val second = message("second")
        val pages = listOf(page(first), page(second))

        val layoutPlan = planHudPaginationReuse(
            previousMessages = listOf(first, second),
            previousPages = pages,
            messages = listOf(first, second),
            layoutCompatible = false,
        )
        val historyPlan = planHudPaginationReuse(
            previousMessages = listOf(first, second),
            previousPages = pages,
            messages = listOf(message("older"), first, second),
            layoutCompatible = true,
        )

        assertEquals(HudPaginationReusePlan(0, 0), layoutPlan)
        assertEquals(HudPaginationReusePlan(0, 0), historyPlan)
    }

    private fun message(id: String, content: String = id): DisplayMessage = DisplayMessage(
        id = id,
        role = "assistant",
        content = content,
    )

    private fun fragment(message: DisplayMessage): HudMessageFragment = HudMessageFragment(
        message = message,
        content = message.content,
        startOffset = 0,
        endOffset = message.content.length,
        showThumbnails = false,
    )

    private fun page(message: DisplayMessage): HudPage = HudPage(listOf(fragment(message)))
}
