package com.clawsses.glasses.input

import com.clawsses.shared.ModelPaging

internal enum class ModelPageSelection {
    CURRENT,
    FIRST,
    LAST,
}

internal data class ModelPickerMove(
    val selectedIndex: Int? = null,
    val requestedOffset: Int? = null,
    val pageSelection: ModelPageSelection = ModelPageSelection.CURRENT,
)

internal object ModelPickerNavigation {
    fun forward(
        selectedIndex: Int,
        itemCount: Int,
        nextOffset: Int?,
    ): ModelPickerMove = when {
        itemCount <= 0 -> ModelPickerMove()
        selectedIndex < itemCount - 1 -> ModelPickerMove(selectedIndex = selectedIndex + 1)
        nextOffset != null -> ModelPickerMove(
            requestedOffset = nextOffset,
            pageSelection = ModelPageSelection.FIRST,
        )
        else -> ModelPickerMove(selectedIndex = itemCount - 1)
    }

    fun backward(
        selectedIndex: Int,
        itemCount: Int,
        pageOffset: Int,
    ): ModelPickerMove = when {
        itemCount <= 0 -> ModelPickerMove()
        selectedIndex > 0 -> ModelPickerMove(selectedIndex = selectedIndex - 1)
        pageOffset > 0 -> ModelPickerMove(
            requestedOffset = (pageOffset - ModelPaging.PAGE_SIZE).coerceAtLeast(0),
            pageSelection = ModelPageSelection.LAST,
        )
        else -> ModelPickerMove(selectedIndex = 0)
    }

    fun initialIndex(
        itemCount: Int,
        currentIndexOnPage: Int?,
        pageSelection: ModelPageSelection,
    ): Int = when {
        itemCount <= 0 -> 0
        pageSelection == ModelPageSelection.FIRST -> 0
        pageSelection == ModelPageSelection.LAST -> itemCount - 1
        currentIndexOnPage != null -> currentIndexOnPage.coerceIn(0, itemCount - 1)
        else -> 0
    }
}
