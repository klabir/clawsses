package com.clawsses.phone.voice

import com.clawsses.shared.VisionCommands
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VisionCommandsTest {
    @Test
    fun `all advertised vision commands produce a prompt`() {
        VisionCommands.phrases.forEach { phrase ->
            assertNotNull("Missing prompt for $phrase", VisionCommands.promptFor(phrase))
        }
    }

    @Test
    fun `ordinary speech is not treated as a vision command`() {
        assertNull(VisionCommands.promptFor("what is the weather"))
    }
}
