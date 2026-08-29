package com.clawsses.phone.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
        setContent {
            ClawssesTheme {
                val store = remember { BoundedChatStore() }
                val messages by store.messages.collectAsState()
                var running by remember { mutableStateOf(false) }
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
                }
                LaunchedEffect(running) {
                    if (!running) return@LaunchedEffect
                    completed = false
                    repeat(1_000) { update ->
                        store.updateStreaming("live-tail", "Streaming response ${"word ".repeat(update % 120)}")
                        delay(1)
                    }
                    store.upsertCompleted(
                        ChatMessage(id = "live-tail", role = "assistant", content = "Streaming complete"),
                    )
                    running = false
                    completed = true
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        Button(onClick = { running = true }, enabled = !running) {
                            Text(if (running) "Benchmark running" else "Run stream benchmark")
                        }
                        if (completed) Text("Benchmark complete")
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
}
