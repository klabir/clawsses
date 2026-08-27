package com.clawsses.glasses.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMessageMailboxTest {

    @Test
    fun messagePublishedBeforeCollectorIsDelivered() = runBlocking {
        val mailbox = PhoneMessageMailbox()

        assertTrue(mailbox.publish("state-before-activity"))

        assertEquals(
            "state-before-activity",
            withTimeout(1_000L) { mailbox.messages.first() },
        )
    }
}
