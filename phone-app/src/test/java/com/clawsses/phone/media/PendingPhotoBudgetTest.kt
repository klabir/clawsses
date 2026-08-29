package com.clawsses.phone.media

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingPhotoBudgetTest {
    @Test
    fun keepsNewestPhotosWithinCountAndByteBudgets() {
        val photos = (1L..6L).map { index ->
            PendingPhoto("$index", "/$index", 4L, index)
        }

        val retained = PendingPhotoBudget.retained(photos, maxCount = 3, maxBytes = 16L)

        assertEquals(listOf("4", "5", "6"), retained.map(PendingPhoto::id))
    }

    @Test
    fun skipsOversizedNewestPhotoWithoutEvictingValidEntries() {
        val photos = listOf(
            PendingPhoto("old", "/old", 4L, 1L),
            PendingPhoto("new", "/new", 20L, 2L),
        )

        val retained = PendingPhotoBudget.retained(photos, maxCount = 4, maxBytes = 16L)

        assertEquals(listOf("old"), retained.map(PendingPhoto::id))
    }
}
