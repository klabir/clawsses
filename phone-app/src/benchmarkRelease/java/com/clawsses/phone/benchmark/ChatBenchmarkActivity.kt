package com.clawsses.phone.benchmark

import android.os.Bundle
import android.os.Trace
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.clawsses.phone.openclaw.BoundedChatStore
import com.clawsses.phone.ui.theme.ClawssesTheme
import com.clawsses.shared.ChatMessage
import kotlinx.coroutines.delay

/** Deterministic, benchmark-build-only chat workload. */
class ChatBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChatBenchmarkStatusProvider.reset()
        setContent {
            ClawssesTheme {
                val store = remember { BoundedChatStore() }
                val messages by store.messages.collectAsState()
                var completed by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    store.replace(
                        (1..500).map { index ->
                            ChatMessage(
                                id = "history-$index",
                                role = if (index % 2 == 0) "assistant" else "user",
                                content = "History line $index with enough text to exercise wrapping.",
                            )
                        },
                    )
                    completed = false
                    Trace.beginSection(TRACE_SECTION)
                    try {
                        var pendingText = ""
                        repeat(1_000) { update ->
                            pendingText = "Streaming response ${"word ".repeat(update % 120)}"
                            if ((update + 1) % DELTAS_PER_PUBLICATION == 0) {
                                store.updateStreaming("live-tail", pendingText)
                            }
                            delay(INCOMING_DELTA_INTERVAL_MS)
                        }
                        store.upsertCompleted(
                            ChatMessage(id = "live-tail", role = "assistant", content = "Streaming complete"),
                        )
                    } finally {
                        Trace.endSection()
                    }
                    completed = true
                    ChatBenchmarkStatusProvider.markComplete()
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        Text(if (completed) "Benchmark complete" else "Benchmark running")
                        Text("Live tail: ${messages.lastOrNull()?.content.orEmpty()}")
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(messages, key = ChatMessage::id) { message ->
                                Text("${message.role}: ${message.content}")
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TRACE_SECTION = "clawsses_chat_stream_1000"
        const val DELTAS_PER_PUBLICATION = 10
        const val INCOMING_DELTA_INTERVAL_MS = 10L
    }
}
