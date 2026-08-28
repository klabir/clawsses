package com.clawsses.glasses.camera

data class ImageDimensions(val width: Int, val height: Int)

object ImageSizing {
    fun decodeSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= targetWidth &&
            sourceHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        return sample
    }

    fun fitInside(
        sourceWidth: Int,
        sourceHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
    ): ImageDimensions {
        require(sourceWidth > 0 && sourceHeight > 0 && maxWidth > 0 && maxHeight > 0)
        val scale = minOf(
            maxWidth.toFloat() / sourceWidth,
            maxHeight.toFloat() / sourceHeight,
            1f,
        )
        return ImageDimensions(
            width = (sourceWidth * scale).toInt().coerceAtLeast(1),
            height = (sourceHeight * scale).toInt().coerceAtLeast(1),
        )
    }

    fun selectCaptureSize(
        sizes: List<ImageDimensions>,
        maxWidth: Int,
        maxHeight: Int,
    ): ImageDimensions? {
        if (sizes.isEmpty()) return null
        return sizes
            .filter { it.width <= maxWidth && it.height <= maxHeight }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
    }
}
