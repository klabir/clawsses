package com.clawsses.phone.ui

import com.clawsses.shared.ChatScrollCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollCoordinatorTest {
    @Test fun `stream follows while reader remains at tail`() {
        val coordinator = ChatScrollCoordinator()
        assertTrue(coordinator.shouldFollowNewContent())
        coordinator.onViewportChanged(atEnd = true, userDriven = false)
        assertTrue(coordinator.shouldFollowNewContent())
    }

    @Test fun `user reading older content is not pulled to tail`() {
        val coordinator = ChatScrollCoordinator()
        coordinator.onViewportChanged(atEnd = false, userDriven = true)
        assertEquals(ChatScrollCoordinator.Mode.USER_READING, coordinator.mode)
        assertFalse(coordinator.shouldFollowNewContent())
    }

    @Test fun `explicit bottom resumes tail following`() {
        val coordinator = ChatScrollCoordinator(initiallyFollowingTail = false)
        coordinator.onExplicitScroll(targetIndex = 9, lastIndex = 9)
        assertTrue(coordinator.shouldFollowNewContent())
    }

    @Test fun `history restore never enables tail prematurely`() {
        val coordinator = ChatScrollCoordinator()
        coordinator.beginHistoryRestore()
        coordinator.onViewportChanged(atEnd = true, userDriven = false)
        assertEquals(ChatScrollCoordinator.Mode.RESTORING_HISTORY, coordinator.mode)
        coordinator.finishHistoryRestore(atEnd = false)
        assertFalse(coordinator.shouldFollowNewContent())
    }
}
