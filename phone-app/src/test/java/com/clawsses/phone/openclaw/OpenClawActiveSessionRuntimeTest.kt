package com.clawsses.phone.openclaw

import com.clawsses.phone.media.ChatAttachmentFileStore
import com.clawsses.shared.ChatMessage
import com.clawsses.shared.OpenClawMethods
import com.clawsses.shared.OpenClawResponse
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OpenClawActiveSessionRuntimeTest {
    @Test
    fun `session synchronization subscribes loads history and releases the previous key`() =
        withHarness { harness ->
            val requests = mutableListOf<Pair<String, String?>>()
            harness.sendRequest = { method, params, _ ->
                requests += method to (params?.get("key") ?: params?.get("sessionKey"))
                    ?.asString
                when (method) {
                    OpenClawMethods.CHAT_HISTORY -> historyResponse("$method-${requests.size}")
                    else -> successResponse()
                }
            }

            runBlocking {
                harness.runtime().synchronize(harness.runtime().activate("a"))
                harness.runtime().synchronize(harness.runtime().activate("b"))
            }

            assertEquals(
                listOf(
                    OpenClawMethods.SESSION_MESSAGES_SUBSCRIBE to "a",
                    OpenClawMethods.CHAT_HISTORY to "a",
                    OpenClawMethods.SESSION_MESSAGES_SUBSCRIBE to "b",
                    OpenClawMethods.CHAT_HISTORY to "b",
                    OpenClawMethods.SESSION_MESSAGES_UNSUBSCRIBE to "a",
                ),
                requests,
            )
            assertEquals(listOf("chat.history-4"), harness.chatStore.value().map { it.content })
        }

    @Test
    fun `history response from replaced session cannot publish`() = withHarness { harness ->
        val requestStarted = CompletableDeferred<Unit>()
        val response = CompletableDeferred<OpenClawResponse>()
        harness.sendRequest = { _, _, _ ->
            requestStarted.complete(Unit)
            response.await()
        }
        val published = mutableListOf<List<ChatMessage>>()
        harness.onChatHistory = published::add

        runBlocking {
            val runtime = harness.runtime()
            val stale = runtime.activate("a")
            val load = async { runtime.loadHistory(stale) }
            requestStarted.await()
            runtime.activate("b")
            response.complete(historyResponse("stale"))
            load.await()
        }

        assertTrue(published.isEmpty())
        assertTrue(harness.chatStore.value().isEmpty())
    }

    @Test
    fun `expanded history claim is bounded and prepends only older rows`() = withHarness { harness ->
        harness.sendRequest = { method, _, _ ->
            assertEquals(OpenClawMethods.CHAT_HISTORY, method)
            historyResponse("older", "current")
        }
        val completions = mutableListOf<Pair<Int, Boolean>>()
        harness.onMoreHistoryLoaded = { count, hasMore -> completions += count to hasMore }
        val runtime = harness.runtime()
        runtime.activate("a")
        harness.chatStore.add(ChatMessage(id = "current", role = "user", content = "current"))

        val claim = runtime.claimMoreHistory()
        assertEquals(100, claim?.requestedLimit)
        assertNull(runtime.claimMoreHistory())
        runBlocking { runtime.loadMoreHistory(requireNotNull(claim)) }

        assertEquals(listOf("older", "current"), harness.chatStore.value().map { it.id })
        assertEquals(listOf(1 to false), completions)
        assertEquals(100, harness.catalogSession.currentHistoryLimit)
        assertFalse(harness.catalogSession.isLoadingMoreHistory.value)
    }

    private fun withHarness(test: (RuntimeHarness) -> Unit) {
        val directory = Files.createTempDirectory("active-session-runtime-test").toFile()
        try {
            test(RuntimeHarness(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    private class RuntimeHarness(directory: java.io.File) {
        val catalogSession = OpenClawCatalogSessionComponent()
        val sessionSync = OpenClawSessionSyncCoordinator()
        val chatStore = BoundedChatStore()
        private val attachmentFileStore = ChatAttachmentFileStore(directory)
        var sendRequest:
            suspend (String, JsonObject?, Long?) -> OpenClawResponse = { _, _, _ -> successResponse() }
        var onChatHistory: (List<ChatMessage>) -> Unit = {}
        var onMoreHistoryLoaded: (Int, Boolean) -> Unit = { _, _ -> }

        fun runtime() = OpenClawActiveSessionRuntime(
            catalogSession = catalogSession,
            sessionSync = sessionSync,
            chatStore = chatStore,
            attachmentFileStore = attachmentFileStore,
            sendRequest = { method, params, generation -> sendRequest(method, params, generation) },
            onChatHistory = { onChatHistory(it) },
            onMoreHistoryLoaded = { count, hasMore -> onMoreHistoryLoaded(count, hasMore) },
            logger = NoOpActiveSessionLogger,
        )
    }

    private object NoOpActiveSessionLogger : ActiveSessionLogger {
        override fun debug(message: String) = Unit
        override fun warn(message: String, error: Throwable?) = Unit
        override fun error(message: String, error: Throwable) = Unit
    }

    private companion object {
        fun successResponse() = OpenClawResponse(id = "response", ok = true)

        fun historyResponse(vararg contents: String): OpenClawResponse {
            val messages = contents.joinToString(",") { content ->
                """{"id":"$content","role":"user","content":"$content"}"""
            }
            return OpenClawResponse(
                id = "history",
                ok = true,
                payload = JsonParser.parseString("""{"messages":[$messages]}""").asJsonObject,
            )
        }
    }
}
