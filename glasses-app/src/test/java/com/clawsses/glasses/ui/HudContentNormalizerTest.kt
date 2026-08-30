package com.clawsses.glasses.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HudContentNormalizerTest {
    @Test
    fun `joins model soft wraps`() {
        assertEquals(
            "A sentence continued on the next line.",
            HudContentNormalizer.unwrapSoftLineBreaks("A sentence continued\non the next line."),
        )
    }

    @Test
    fun `preserves paragraphs and markdown structure`() {
        val input = "Heading\n\nParagraph\n- first\n2. second\n> quote\n```\ncode\n```"

        assertEquals(input, HudContentNormalizer.unwrapSoftLineBreaks(input))
    }

    @Test
    fun `returns single line unchanged`() {
        assertEquals("unchanged", HudContentNormalizer.unwrapSoftLineBreaks("unchanged"))
    }
}
