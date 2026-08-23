package com.clawsses.phone.settings

import com.clawsses.shared.ScrollSettings
import com.clawsses.shared.ScrollSettingsUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollSettingsTest {
    @Test
    fun `default scroll step is one message`() {
        assertEquals(1, ScrollSettings.DEFAULT_MESSAGES_PER_STEP)
        assertEquals(1, ScrollSettingsUpdate().messagesPerStep)
    }

    @Test
    fun `scroll step is constrained to the five supported choices`() {
        assertEquals(1, ScrollSettings.normalizeMessagesPerStep(-1))
        assertEquals(3, ScrollSettings.normalizeMessagesPerStep(3))
        assertEquals(5, ScrollSettings.normalizeMessagesPerStep(9))
    }

    @Test
    fun `wire update contains the selected step`() {
        val json = ScrollSettingsUpdate(messagesPerStep = 4).toJson()

        assertTrue(json.contains("\"type\":\"scroll_settings\""))
        assertTrue(json.contains("\"messagesPerStep\":4"))
    }
}
