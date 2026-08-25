package com.clawsses.glasses.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelPickerNavigationTest {
    @Test
    fun `forward selects the next model and requests the next page at the edge`() {
        assertEquals(
            ModelPickerMove(selectedIndex = 2),
            ModelPickerNavigation.forward(selectedIndex = 1, itemCount = 3, nextOffset = 3),
        )
        assertEquals(
            ModelPickerMove(requestedOffset = 3, pageSelection = ModelPageSelection.FIRST),
            ModelPickerNavigation.forward(selectedIndex = 2, itemCount = 3, nextOffset = 3),
        )
    }

    @Test
    fun `backward selects the previous model and opens the previous page at its end`() {
        assertEquals(
            ModelPickerMove(selectedIndex = 0),
            ModelPickerNavigation.backward(selectedIndex = 1, itemCount = 3, pageOffset = 3),
        )
        assertEquals(
            ModelPickerMove(requestedOffset = 0, pageSelection = ModelPageSelection.LAST),
            ModelPickerNavigation.backward(selectedIndex = 0, itemCount = 3, pageOffset = 3),
        )
    }

    @Test
    fun `picker stops at catalog boundaries instead of wrapping`() {
        val first = ModelPickerNavigation.backward(selectedIndex = 0, itemCount = 3, pageOffset = 0)
        val last = ModelPickerNavigation.forward(selectedIndex = 1, itemCount = 2, nextOffset = null)

        assertEquals(0, first.selectedIndex)
        assertNull(first.requestedOffset)
        assertEquals(1, last.selectedIndex)
        assertNull(last.requestedOffset)
    }

    @Test
    fun `page selection preserves navigation direction`() {
        assertEquals(0, ModelPickerNavigation.initialIndex(3, 2, ModelPageSelection.FIRST))
        assertEquals(2, ModelPickerNavigation.initialIndex(3, 0, ModelPageSelection.LAST))
        assertEquals(1, ModelPickerNavigation.initialIndex(3, 1, ModelPageSelection.CURRENT))
        assertEquals(0, ModelPickerNavigation.initialIndex(3, null, ModelPageSelection.CURRENT))
    }
}
