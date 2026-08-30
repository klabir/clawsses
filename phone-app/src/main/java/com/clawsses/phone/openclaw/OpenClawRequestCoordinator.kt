package com.clawsses.phone.openclaw

import com.clawsses.shared.OpenClawResponse
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicLong

internal data class PendingOpenClawRequest(
    val id: String,
    val response: CompletableDeferred<OpenClawResponse>,
)

/** Correlates gateway responses without exposing pending-request state to the transport facade. */
internal class OpenClawRequestCoordinator {
    private val requestSequence = AtomicLong(1)
    private val pending = mutableMapOf<String, CompletableDeferred<OpenClawResponse>>()

    @Synchronized
    fun register(method: String): PendingOpenClawRequest {
        val request = PendingOpenClawRequest(
            id = "$method-${requestSequence.getAndIncrement()}",
            response = CompletableDeferred(),
        )
        pending[request.id] = request.response
        return request
    }

    @Synchronized
    fun cancel(request: PendingOpenClawRequest): Boolean =
        pending.remove(request.id, request.response)

    fun resolve(response: OpenClawResponse): Boolean {
        val request = synchronized(this) { pending.remove(response.id) }
        return request?.complete(response) == true
    }

    fun failAll(reason: String): Int {
        val requests = synchronized(this) {
            pending.values.toList().also { pending.clear() }
        }
        requests.forEach { it.completeExceptionally(Exception(reason)) }
        return requests.size
    }

    @Synchronized
    internal fun pendingCount(): Int = pending.size
}
