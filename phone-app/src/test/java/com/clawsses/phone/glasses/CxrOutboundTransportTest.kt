package com.clawsses.phone.glasses

import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class CxrOutboundTransportTest {
    @Test
    fun `disconnect while awaiting ack does not terminate worker`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val transport = CxrOutboundTransport(
            scope = scope,
            sendDirect = { payload -> sent += payload; true },
            ackTimeoutMs = 2_000L,
        )
        transport.setPeerBuild(CxrOutboundTransport.ACK_PROTOCOL_BUILD)
        transport.setConnected(true)

        transport.enqueue("""{"type":"chat_stream_end","id":"first"}""", bypassWakeGate = true)
        withTimeout(1_000L) { while (sent.isEmpty()) delay(5L) }
        transport.setConnected(false)
        transport.setConnected(true)

        transport.enqueue("""{"type":"chat_stream_end","id":"second"}""", bypassWakeGate = true)
        var inspected = 1
        var secondAcknowledged = false
        withTimeout(2_000L) {
            while (!secondAcknowledged) {
                val snapshot = synchronized(sent) { sent.toList() }
                snapshot.drop(inspected).forEach { payload ->
                    val json = JsonParser.parseString(payload).asJsonObject
                    transport.handleAck(json.get("_tx").asString)
                    if (json.get("id").asString == "second") secondAcknowledged = true
                    inspected++
                }
                delay(5L)
            }
            while (transport.metrics.value.acknowledged < 1L) delay(5L)
        }

        assertTrue(secondAcknowledged)
        assertTrue(synchronized(sent) { sent.any { payload ->
            JsonParser.parseString(payload).asJsonObject.get("id").asString == "second"
        } })
        assertTrue(transport.metrics.value.acknowledged >= 1L)
        transport.cleanup()
        scope.cancel()
    }
}
