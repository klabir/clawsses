package com.clawsses.phone.glasses

import android.util.Log
import com.clawsses.shared.CxrPayloadLimits
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

enum class CxrPriority { CRITICAL, NORMAL, TRANSIENT }

internal val RELIABLE_CXR_TYPES = setOf(
    "chat_stream_end",
    "chat_history_begin",
    "chat_history_chunk",
    "chat_history_end",
    "connection_update",
    "session_operation",
    "model_page",
    "model_operation",
    "run_state",
    "tts_state",
)

data class CxrTransportMetrics(
    val queued: Long = 0,
    val sent: Long = 0,
    val acknowledged: Long = 0,
    val retried: Long = 0,
    val coalesced: Long = 0,
    val dropped: Long = 0,
    val failed: Long = 0,
    val queueDepth: Int = 0,
    val queueHighWater: Int = 0,
)

internal data class CxrQueuedMessage(
    val payload: String,
    val type: String,
    val priority: CxrPriority,
    val coalesceKey: String?,
    val reliable: Boolean,
)

/**
 * Pure bounded priority queue used by [CxrOutboundTransport].
 *
 * The hard capacity is never exceeded. Transient messages are coalesced or dropped first,
 * followed by normal messages and supersedable critical state. Ordered critical packets are
 * rejected only when no safe eviction candidate remains. The queue intentionally does not merge
 * arbitrary JSON: chat stream chunks are concatenated only when the resulting CXR payload fits.
 */
internal class CxrOutboundQueue(private val maxSize: Int = 128) {
    private val messages = ArrayDeque<CxrQueuedMessage>()

    val size: Int get() = messages.size

    data class EnqueueResult(
        val accepted: Boolean = true,
        val coalesced: Boolean = false,
        val dropped: Int = 0,
    )

    fun enqueue(message: CxrQueuedMessage): EnqueueResult {
        if (message.coalesceKey != null) {
            val existing = messages.firstOrNull { it.coalesceKey == message.coalesceKey }
            if (existing != null) {
                val replacement = merge(existing, message)
                if (replacement != null) {
                    val retained = messages.toMutableList()
                    retained[retained.indexOf(existing)] = replacement
                    messages.clear()
                    messages.addAll(retained)
                    return EnqueueResult(coalesced = true)
                }
            }
        }

        var dropped = 0
        while (size >= maxSize) {
            val evictionCandidate = messages.firstOrNull { it.priority == CxrPriority.TRANSIENT }
                ?: messages.firstOrNull {
                    message.priority == CxrPriority.CRITICAL && it.priority == CxrPriority.NORMAL
                }
                ?: messages.firstOrNull {
                    message.priority == CxrPriority.CRITICAL &&
                        it.priority == CxrPriority.CRITICAL &&
                        it.coalesceKey != null
                }
            if (evictionCandidate == null) {
                return EnqueueResult(accepted = false, dropped = dropped + 1)
            }
            messages.remove(evictionCandidate)
            dropped++
        }

        messages.addLast(message)
        return EnqueueResult(dropped = dropped)
    }

    fun poll(): CxrQueuedMessage? = messages.pollFirst()

    fun clearTransient(): Int {
        val count = messages.count { it.priority == CxrPriority.TRANSIENT }
        messages.removeAll { it.priority == CxrPriority.TRANSIENT }
        return count
    }

    private fun merge(
        existing: CxrQueuedMessage,
        incoming: CxrQueuedMessage,
    ): CxrQueuedMessage? {
        if (incoming.type != "chat_stream") return incoming

        return runCatching {
            val existingJson = JsonParser.parseString(existing.payload).asJsonObject
            val incomingJson = JsonParser.parseString(incoming.payload).asJsonObject
            val mergedJson = JsonObject().apply {
                existingJson.entrySet().forEach { (key, value) -> add(key, value) }
                addProperty(
                    "chunk",
                    existingJson.get("chunk")?.asString.orEmpty() +
                        incomingJson.get("chunk")?.asString.orEmpty(),
                )
            }
            val payload = mergedJson.toString()
            if (CxrPayloadLimits.fits(payload)) incoming.copy(payload = payload) else null
        }.getOrNull()
    }
}

/**
 * Process-scoped, serial phone-to-glasses transport.
 *
 * The existing wake manager feeds this transport only when delivery is allowed. Reliable ACKs
 * are negotiated by glasses build number so a newly installed phone remains compatible with the
 * previous glasses build during a paired update.
 */
class CxrOutboundTransport(
    private val scope: CoroutineScope,
    private val sendDirect: (String) -> Boolean,
    private val ackTimeoutMs: Long = 500L,
    private val maxAttempts: Int = 3,
) {
    companion object {
        private const val TAG = "CxrOutboundTransport"
        const val ACK_PROTOCOL_BUILD = 26
        const val ACK_METADATA_RESERVE_BYTES = 48
    }

    private val lock = Any()
    private val queue = CxrOutboundQueue()
    private val wakeWorker = Channel<Unit>(Channel.CONFLATED)
    private val nextTransaction = AtomicLong()
    private val pendingAcks = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val _metrics = MutableStateFlow(CxrTransportMetrics())
    val metrics: StateFlow<CxrTransportMetrics> = _metrics.asStateFlow()

    @Volatile private var connected = false
    @Volatile private var acknowledgmentsSupported = false
    @Volatile private var epoch = 0L
    private val worker: Job = scope.launch { workerLoop() }

    fun setPeerBuild(versionCode: Int?) {
        acknowledgmentsSupported = versionCode != null && versionCode >= ACK_PROTOCOL_BUILD
    }

    fun setConnected(value: Boolean) {
        connected = value
        if (value) {
            epoch++
            wakeWorker.trySend(Unit)
        } else {
            val dropped = synchronized(lock) { queue.clearTransient() }
            synchronized(lock) {
                pendingAcks.values.forEach { it.cancel() }
                pendingAcks.clear()
            }
            updateMetrics(dropped = dropped.toLong())
        }
    }

    fun enqueue(payload: String) {
        val message = classify(payload) ?: run {
            updateMetrics(failed = 1)
            return
        }
        val result = synchronized(lock) { queue.enqueue(message) }
        updateMetrics(
            queued = 1,
            coalesced = if (result.coalesced) 1 else 0,
            dropped = result.dropped.toLong(),
            failed = if (!result.accepted && message.priority == CxrPriority.CRITICAL) 1 else 0,
        )
        wakeWorker.trySend(Unit)
    }

    fun handleAck(transactionId: String) {
        val deferred = synchronized(lock) { pendingAcks.remove(transactionId) } ?: return
        deferred.complete(Unit)
    }

    fun cleanup() {
        worker.cancel()
        wakeWorker.close()
        synchronized(lock) {
            pendingAcks.values.forEach { it.cancel() }
            pendingAcks.clear()
        }
    }

    fun logMetrics(reason: String) {
        val snapshot = metrics.value
        Log.i(
            TAG,
            "metrics reason=$reason queued=${snapshot.queued} sent=${snapshot.sent} " +
                "acked=${snapshot.acknowledged} retry=${snapshot.retried} " +
                "coalesced=${snapshot.coalesced} dropped=${snapshot.dropped} " +
                "failed=${snapshot.failed} depth=${snapshot.queueDepth} " +
                "highWater=${snapshot.queueHighWater}",
        )
    }

    private suspend fun workerLoop() {
        for (ignored in wakeWorker) {
            while (connected) {
                val message = synchronized(lock) { queue.poll() } ?: break
                deliver(message)
                refreshDepth()
            }
        }
    }

    private suspend fun deliver(message: CxrQueuedMessage) {
        val useAck = acknowledgmentsSupported && message.reliable
        val requestedTransactionId = if (useAck) {
            "${epoch.toString(36)}-${nextTransaction.incrementAndGet().toString(36)}"
        } else {
            null
        }
        val acknowledgedPayload = requestedTransactionId?.let {
            addTransaction(message.payload, it)
        }
        val transactionId = requestedTransactionId?.takeIf {
            acknowledgedPayload != null && CxrPayloadLimits.fits(acknowledgedPayload)
        }
        val wirePayload = if (transactionId != null) acknowledgedPayload!! else message.payload
        if (requestedTransactionId != null && transactionId == null) {
            Log.w(TAG, "ACK metadata exceeds CXR limit; sending once without ACK: type=${message.type}")
        }
        if (!CxrPayloadLimits.fits(wirePayload)) {
            Log.e(TAG, "Refusing oversized queued message: type=${message.type}")
            updateMetrics(failed = 1)
            return
        }

        repeat(maxAttempts) { attempt ->
            if (!connected) {
                requeueAfterDisconnect(message)
                return
            }
            val ack = transactionId?.let { CompletableDeferred<Unit>() }
            if (transactionId != null && ack != null) {
                synchronized(lock) { pendingAcks[transactionId] = ack }
            }

            if (!sendDirect(wirePayload)) {
                synchronized(lock) { pendingAcks.remove(transactionId)?.cancel() }
            } else if (ack == null) {
                updateMetrics(sent = 1)
                return
            } else if (withTimeoutOrNull(ackTimeoutMs) { ack.await(); true } == true) {
                updateMetrics(sent = 1, acknowledged = 1)
                return
            } else {
                synchronized(lock) { pendingAcks.remove(transactionId)?.cancel() }
            }

            if (attempt + 1 < maxAttempts) {
                updateMetrics(retried = 1)
                delay(250L shl attempt)
            }
        }

        Log.w(TAG, "Delivery failed after retries: type=${message.type}")
        updateMetrics(failed = 1)
    }

    private fun requeueAfterDisconnect(message: CxrQueuedMessage) {
        if (message.priority == CxrPriority.TRANSIENT) {
            updateMetrics(dropped = 1)
            return
        }
        val result = synchronized(lock) { queue.enqueue(message) }
        updateMetrics(
            coalesced = if (result.coalesced) 1 else 0,
            dropped = result.dropped.toLong(),
            failed = if (!result.accepted && message.priority == CxrPriority.CRITICAL) 1 else 0,
        )
    }

    private fun classify(payload: String): CxrQueuedMessage? = runCatching {
        val json = JsonParser.parseString(payload).asJsonObject
        val type = json.get("type")?.asString.orEmpty()
        val id = json.get("id")?.asString
        val reliable = type in RELIABLE_CXR_TYPES
        val coalesceKey = when (type) {
            "chat_stream" -> "chat_stream:${id.orEmpty()}"
            "agent_progress" -> "agent_progress:${id.orEmpty()}"
            "live_caption" -> "live_caption"
            "battery_update" -> "battery_update"
            "time_update" -> "time_update"
            "connection_update" -> "connection_update"
            "run_state" -> "run_state"
            "tts_state" -> "tts_state"
            "session_operation" -> "session_operation:${json.primitiveString("operation").orEmpty()}"
            "model_operation" -> "model_operation"
            "model_page" -> buildString {
                append("model_page:")
                append(json.primitiveString("c").orEmpty())
                append(':')
                append(json.primitiveString("pi").orEmpty())
            }
            else -> null
        }
        val priority = when {
            reliable -> CxrPriority.CRITICAL
            coalesceKey != null -> CxrPriority.TRANSIENT
            else -> CxrPriority.NORMAL
        }
        CxrQueuedMessage(payload, type, priority, coalesceKey, reliable)
    }.getOrNull()

    private fun addTransaction(payload: String, transactionId: String): String {
        val json = JsonParser.parseString(payload).asJsonObject
        json.addProperty("_tx", transactionId)
        return json.toString()
    }

    private fun updateMetrics(
        queued: Long = 0,
        sent: Long = 0,
        acknowledged: Long = 0,
        retried: Long = 0,
        coalesced: Long = 0,
        dropped: Long = 0,
        failed: Long = 0,
    ) {
        synchronized(lock) {
            val depth = queue.size
            val current = _metrics.value
            _metrics.value = current.copy(
                queued = current.queued + queued,
                sent = current.sent + sent,
                acknowledged = current.acknowledged + acknowledged,
                retried = current.retried + retried,
                coalesced = current.coalesced + coalesced,
                dropped = current.dropped + dropped,
                failed = current.failed + failed,
                queueDepth = depth,
                queueHighWater = maxOf(current.queueHighWater, depth),
            )
        }
    }

    private fun refreshDepth() = updateMetrics()
}

private fun JsonObject.primitiveString(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString
