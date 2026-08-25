package com.clawsses.phone.ui

import com.clawsses.shared.HudPageNavigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudPageNavigatorTest {
    @Test fun `live mode advances only when the document gains a page`() {
        val navigator = HudPageNavigator()
        navigator.onDocumentChanged(pageCount = 3)
        assertEquals(2, navigator.pageIndex)

        navigator.onDocumentChanged(pageCount = 3)
        assertEquals(2, navigator.pageIndex)

        navigator.onDocumentChanged(pageCount = 4)
        assertEquals(3, navigator.pageIndex)
    }

    @Test fun `manual previous page freezes live following`() {
        val navigator = HudPageNavigator()
        navigator.onDocumentChanged(pageCount = 5)
        navigator.moveBy(delta = -1, pageCount = 5)

        assertEquals(3, navigator.pageIndex)
        assertEquals(HudPageNavigator.Mode.USER_READING, navigator.mode)
        assertTrue(navigator.hasNewerPages(pageCount = 5))

        navigator.onDocumentChanged(pageCount = 6, restoredAnchorPage = 3)
        assertEquals(3, navigator.pageIndex)
    }

    @Test fun `next page resumes live mode at the tail`() {
        val navigator = HudPageNavigator()
        navigator.onDocumentChanged(pageCount = 4)
        navigator.moveBy(delta = -2, pageCount = 4)
        navigator.moveBy(delta = 2, pageCount = 4)

        assertEquals(3, navigator.pageIndex)
        assertEquals(HudPageNavigator.Mode.FOLLOW_LIVE, navigator.mode)
        assertFalse(navigator.hasNewerPages(pageCount = 4))
    }

    @Test fun `history prepend restores the anchored page`() {
        val navigator = HudPageNavigator()
        navigator.onDocumentChanged(pageCount = 6)
        navigator.moveBy(delta = -3, pageCount = 6)
        navigator.beginAnchorRestore()
        navigator.onDocumentChanged(pageCount = 9, restoredAnchorPage = 5)

        assertEquals(5, navigator.pageIndex)
        assertEquals(HudPageNavigator.Mode.USER_READING, navigator.mode)
    }

    @Test fun `single page history request can hold its anchor`() {
        val navigator = HudPageNavigator()
        navigator.onDocumentChanged(pageCount = 1)
        navigator.holdCurrentPage()
        navigator.onDocumentChanged(pageCount = 4, restoredAnchorPage = 2)

        assertEquals(2, navigator.pageIndex)
        assertEquals(HudPageNavigator.Mode.USER_READING, navigator.mode)
    }

    @Test fun `jump latest always resumes live following`() {
        val navigator = HudPageNavigator()
        navigator.onDocumentChanged(pageCount = 8)
        navigator.moveBy(delta = -4, pageCount = 8)
        navigator.jumpToLatest(pageCount = 9)

        assertEquals(8, navigator.pageIndex)
        assertEquals(HudPageNavigator.Mode.FOLLOW_LIVE, navigator.mode)
    }
}
