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
    fun `history refresh queues exactly one trailing cycle`() {
        val sync = OpenClawSessionSyncCoordinator()
        val claim = sync.claimHistoryRefresh()!!
        assertNull(sync.claimHistoryRefresh())
        assertNull(sync.claimHistoryRefresh())
        assertTrue(sync.completeHistoryRefreshCycle(claim))
        assertFalse(sync.completeHistoryRefreshCycle(claim))
        assertTrue(sync.claimHistoryRefresh() != null)
    }

    @Test
    fun `stale refresh completion cannot release a newer claim`() {
        val sync = OpenClawSessionSyncCoordinator()
        val stale = sync.claimHistoryRefresh()!!
        sync.resetConnection()
        val current = sync.claimHistoryRefresh()!!

        assertFalse(sync.completeHistoryRefreshCycle(stale))
        sync.releaseHistoryRefresh(stale)
        assertNull(sync.claimHistoryRefresh())
        assertTrue(sync.completeHistoryRefreshCycle(current))
        assertFalse(sync.completeHistoryRefreshCycle(current))
    }

    @Test
    fun `parses gateway message identity sequence and idempotency`() {
        val payload = JsonParser.parseString(
            """{
              "sessionKey":"agent:main:main",
              "messageId":"envelope-id",
              "messageSeq":99,
              "message":{"role":"user","content":"same","__openclaw":{
                "id":"canonical","idempotencyKey":"run-7","seq":7
              }}
            }""",
        ).asJsonObject
        val parsed = SessionMessageEventParser.parse(payload)!!
        assertEquals("canonical", parsed.message.id)
        assertEquals("run-7", parsed.idempotencyKey)
        assertEquals(7L, parsed.messageSeq)
    }

    @Test
    fun `envelope identity is retained when transcript metadata is absent`() {
        val payload = JsonParser.parseString(
            """{
              "sessionKey":"agent:main:main",
              "messageId":"envelope-id",
              "messageSeq":9,
              "clientRunId":"envelope-run",
              "message":{"role":"user","content":"fallback"}
            }""",
        ).asJsonObject

        val parsed = SessionMessageEventParser.parse(payload)!!

        assertEquals("envelope-id", parsed.message.id)
        assertEquals("envelope-run", parsed.idempotencyKey)
        assertEquals(9L, parsed.messageSeq)
    }

    @Test
    fun `history and live event preserve the same transcript identity`() {
        val rawMessage = JsonParser.parseString(
            """{"role":"user","content":"same","__openclaw":{
              "id":"canonical","idempotencyKey":"run-7","seq":7
            }}""",
        ).asJsonObject
        val payload = JsonParser.parseString(
            """{"sessionKey":"agent:main:main","message":$rawMessage}""",
        ).asJsonObject

        val history = OpenClawChatHistoryParser.parseMessage("agent:main:main", rawMessage)!!
        val live = SessionMessageEventParser.parse(payload)!!.message

        assertEquals("canonical", history.id)
        assertEquals(history.id, live.id)
    }

    @Test
    fun `real gateway echo replaces one optimistic row end to end`() {
        val sync = OpenClawSessionSyncCoordinator()
        val store = BoundedChatStore()
        sync.activate("agent:main:main")
        sync.confirmSubscription("agent:main:main")
        sync.registerOptimistic("agent:main:main", "run-7", "local-7")
        store.add(
            com.clawsses.shared.ChatMessage(
                id = "local-7",
                role = "user",
                content = "same",
            ),
        )
        val payload = JsonParser.parseString(
            """{
              "sessionKey":"agent:main:main",
              "messageId":"canonical-7",
              "messageSeq":7,
              "message":{"role":"user","content":"same","__openclaw":{
                "id":"canonical-7","idempotencyKey":"run-7","seq":7
              }}
            }""",
        ).asJsonObject

        val parsed = SessionMessageEventParser.parse(payload)!!
        val decision = sync.acceptMessage(parsed)
            as OpenClawSessionSyncCoordinator.MessageDecision.Accept
        val result = store.reconcileCanonical(parsed.message, decision.replacingLocalId)

        assertEquals(listOf("canonical-7"), result.messages.map { it.id })
        assertEquals("same", result.messages.single().content)
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
