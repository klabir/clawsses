package com.clawsses.phone.openclaw

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawSessionSyncCoordinatorTest {
    @Test
    fun `rejects messages until active session subscription is confirmed`() {
        val sync = OpenClawSessionSyncCoordinator()
        sync.activate("a")
        assertEquals(
            OpenClawSessionSyncCoordinator.MessageDecision.Ignore,
            sync.acceptMessage(event("a", "m1", 1)),
        )
        assertNull(sync.confirmSubscription("a"))
        assertTrue(
            sync.acceptMessage(event("a", "m1", 1)) is
                OpenClawSessionSyncCoordinator.MessageDecision.Accept,
        )
    }

    @Test
    fun `stale session events are ignored after switch`() {
        val sync = OpenClawSessionSyncCoordinator()
        sync.activate("a")
        sync.confirmSubscription("a")
        sync.activate("b")
        sync.confirmSubscription("b")
        assertEquals(
            OpenClawSessionSyncCoordinator.MessageDecision.Ignore,
            sync.acceptMessage(event("a", "late", 2)),
        )
    }

    @Test
    fun `optimistic echo is correlated only by idempotency key`() {
        val sync = OpenClawSessionSyncCoordinator()
        sync.activate("a")
        sync.confirmSubscription("a")
        sync.registerOptimistic("a", "run-1", "local-1")
        val decision = sync.acceptMessage(event("a", "canonical-1", 1, "run-1"))
            as OpenClawSessionSyncCoordinator.MessageDecision.Accept
        assertEquals("local-1", decision.replacingLocalId)
        assertFalse(decision.sequenceGap)
    }

    @Test
    fun `distinct ids with identical text remain distinct and sequence gaps reconcile`() {
        val sync = OpenClawSessionSyncCoordinator()
        sync.activate("a")
        sync.confirmSubscription("a")
        val first = sync.acceptMessage(event("a", "m1", 1))
            as OpenClawSessionSyncCoordinator.MessageDecision.Accept
        val second = sync.acceptMessage(event("a", "m2", 3))
            as OpenClawSessionSyncCoordinator.MessageDecision.Accept
        assertNull(first.replacingLocalId)
        assertNull(second.replacingLocalId)
        assertTrue(second.sequenceGap)
    }

    @Test
    fun `history refresh claims coalesce until completion`() {
        val sync = OpenClawSessionSyncCoordinator()
        assertTrue(sync.claimHistoryRefresh())
        assertFalse(sync.claimHistoryRefresh())
        sync.completeHistoryRefresh()
        assertTrue(sync.claimHistoryRefresh())
    }

    @Test
    fun `parses gateway message identity sequence and idempotency`() {
        val payload = JsonParser.parseString(
            """{
              "sessionKey":"agent:main:main",
              "messageId":"canonical",
              "messageSeq":7,
              "message":{"role":"user","content":"same","idempotencyKey":"run-7"}
            }""",
        ).asJsonObject
        val parsed = SessionMessageEventParser.parse(payload)!!
        assertEquals("canonical", parsed.message.id)
        assertEquals("run-7", parsed.idempotencyKey)
        assertEquals(7L, parsed.messageSeq)
    }

    private fun event(
        sessionKey: String,
        id: String,
        seq: Long,
        idempotencyKey: String? = null,
    ) = ParsedSessionMessage(
        sessionKey = sessionKey,
        message = com.clawsses.shared.ChatMessage(id = id, role = "user", content = "same"),
        idempotencyKey = idempotencyKey,
        messageSeq = seq,
    )
}
