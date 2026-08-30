package com.clawsses.glasses.orchestration

import com.clawsses.glasses.protocol.PhoneHudDecodeResult
import com.clawsses.glasses.protocol.PhoneHudMessage
import com.clawsses.glasses.protocol.PhoneHudMessageCodec

sealed interface HudPhoneMessageResult {
    data object Delivered : HudPhoneMessageResult
    data class Duplicate(val transactionId: String) : HudPhoneMessageResult
    data class Malformed(val type: String?, val reason: String) : HudPhoneMessageResult
    data class Unknown(val type: String) : HudPhoneMessageResult
    data class HandlerFailed(val error: Exception) : HudPhoneMessageResult
}

/**
 * Owns the production Phone-to-HUD ingress contract: decode, bounded replay detection and ACKs.
 * Typed UI effects remain injected so the Activity does not expose transport bookkeeping.
 */
class HudPhoneMessageController(
    private val metrics: HudRuntimeMetrics,
    private val transactions: HudTransportTransactionTracker = HudTransportTransactionTracker(),
    private val acknowledge: (String) -> Unit,
    private val deliver: (PhoneHudMessage) -> Unit,
) {
    fun accept(raw: String): HudPhoneMessageResult {
        metrics.recordPhoneMessage()
        return when (val decoded = PhoneHudMessageCodec.decode(raw)) {
            is PhoneHudDecodeResult.Success -> accept(decoded)
            is PhoneHudDecodeResult.Malformed -> {
                metrics.recordMalformedMessage()
                decoded.transactionId?.let(::recordAndAcknowledge)
                HudPhoneMessageResult.Malformed(decoded.type, decoded.reason)
            }
            is PhoneHudDecodeResult.UnknownType -> {
                decoded.transactionId?.let(::recordAndAcknowledge)
                HudPhoneMessageResult.Unknown(decoded.type)
            }
        }
    }

    private fun accept(decoded: PhoneHudDecodeResult.Success): HudPhoneMessageResult {
        val transactionId = decoded.envelope.transactionId
        if (transactionId != null && transactions.contains(transactionId)) {
            metrics.recordDuplicateTransaction()
            acknowledge(transactionId)
            return HudPhoneMessageResult.Duplicate(transactionId)
        }
        return try {
            deliver(decoded.envelope.message)
            transactionId?.let(::recordAndAcknowledge)
            HudPhoneMessageResult.Delivered
        } catch (error: Exception) {
            metrics.recordMalformedMessage()
            HudPhoneMessageResult.HandlerFailed(error)
        }
    }

    private fun recordAndAcknowledge(transactionId: String) {
        transactions.record(transactionId)
        acknowledge(transactionId)
    }
}
