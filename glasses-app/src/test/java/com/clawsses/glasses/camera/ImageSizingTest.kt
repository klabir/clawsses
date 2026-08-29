package com.clawsses.glasses.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSizingTest {
    @Test
    fun `decode sampling uses powers of two without undershooting target`() {
        assertEquals(2, ImageSizing.decodeSampleSize(2560, 1440, 1280, 720))
        assertEquals(2, ImageSizing.decodeSampleSize(4000, 3000, 1280, 720))
        assertEquals(1, ImageSizing.decodeSampleSize(1280, 720, 1280, 720))
    }

    @Test
    fun `fit inside preserves aspect ratio and never upscales`() {
        assertEquals(ImageDimensions(1280, 720), ImageSizing.fitInside(2560, 1440, 1280, 720))
        assertEquals(ImageDimensions(720, 720), ImageSizing.fitInside(1000, 1000, 1280, 720))
        assertEquals(ImageDimensions(640, 480), ImageSizing.fitInside(640, 480, 1280, 720))
    }

    @Test
    fun `capture size prefers largest bounded option then smallest fallback`() {
        val sizes = listOf(
            ImageDimensions(4000, 3000),
            ImageDimensions(1280, 720),
            ImageDimensions(640, 480),
        )

        assertEquals(ImageDimensions(1280, 720), ImageSizing.selectCaptureSize(sizes, 1280, 720))
        assertEquals(ImageDimensions(640, 480), ImageSizing.selectCaptureSize(sizes, 320, 240))
    }
}
