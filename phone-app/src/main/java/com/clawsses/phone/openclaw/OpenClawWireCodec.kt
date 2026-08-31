package com.clawsses.phone.openclaw

import com.clawsses.shared.OpenClawEvent
import com.clawsses.shared.OpenClawResponse
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal sealed interface GatewayFrame {
    data class Response(val value: OpenClawResponse) : GatewayFrame
    data class Event(val value: OpenClawEvent) : GatewayFrame
    data class Unknown(val type: String?) : GatewayFrame
    data class Malformed(val reason: String) : GatewayFrame
}

internal object OpenClawWireCodec {
    fun decode(raw: String): GatewayFrame = try {
        val json = JsonParser.parseString(raw).asJsonObject
        when (val type = json.string("type")) {
            "res" -> GatewayFrame.Response(
                OpenClawResponse(
                    id = json.requiredString("id"),
                    ok = json.booleanOrDefault("ok", false),
                    payload = json.objectOrNull("payload"),
                    error = json.objectOrNull("error"),
                ),
            )
            "event" -> GatewayFrame.Event(
                OpenClawEvent(
                    event = json.requiredString("event"),
                    payload = json.objectOrNull("payload"),
                    seq = json.longOrNull("seq"),
                    stateVersion = json.longOrNull("stateVersion"),
                ),
            )
            else -> GatewayFrame.Unknown(type)
        }
    } catch (error: Exception) {
        GatewayFrame.Malformed(error.message ?: "invalid JSON")
    }

    private fun JsonObject.element(name: String) = get(name)?.takeUnless { it.isJsonNull }
    private fun JsonObject.string(name: String): String? =
        element(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.requiredString(name: String): String =
        string(name) ?: error("$name must be a string")
    private fun JsonObject.booleanOrDefault(name: String, default: Boolean): Boolean =
        element(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default
    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        element(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.longOrNull(name: String): Long? =
        element(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asLong }.getOrNull() }
}

internal data class ParsedChatEvent(
    val state: String,
    val runId: String?,
    val sessionKey: String?,
    val fullText: String,
    val errorMessage: String?,
)

internal object ChatEventParser {
    fun parse(payload: JsonObject?): ParsedChatEvent? {
        payload ?: return null
        val state = payload.string("state") ?: return null
        return ParsedChatEvent(
            state = state,
            runId = payload.string("runId"),
            sessionKey = payload.string("sessionKey"),
            fullText = payload.textContent(),
            errorMessage = payload.string("errorMessage"),
        )
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.textContent(): String {
        val blocks = getAsJsonObject("message")?.getAsJsonArray("content") ?: return ""
        return buildString {
            blocks.forEach { element ->
                val block = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                if (block.string("type") == "text") block.string("text")?.let(::append)
            }
        }
    }
}

internal object OpenClawAuthRequestFactory {
    private const val CLIENT_ID = "openclaw-android"
    private const val CLIENT_MODE = "ui"
    private const val ROLE = "operator"
    private val SCOPES = listOf("operator.read", "operator.write")

    fun clientInfo(appVersion: String): JsonObject = JsonObject().apply {
        addProperty("id", CLIENT_ID)
        addProperty("version", appVersion)
        addProperty("platform", "android")
        addProperty("mode", CLIENT_MODE)
    }

    fun create(
        protocolVersion: Int,
        appVersion: String,
        token: String,
        nonce: String,
        deviceIdentity: DeviceIdentity,
        signedAtMs: Long,
    ): JsonObject = JsonObject().apply {
        addProperty("minProtocol", protocolVersion)
        addProperty("maxProtocol", protocolVersion)
        add("client", clientInfo(appVersion))
        addProperty("role", ROLE)
        add("scopes", JsonArray().apply { SCOPES.forEach(::add) })
        add("caps", JsonArray().apply { add("session-scoped-events") })
        add("auth", JsonObject().apply { addProperty("token", token) })
        add("device", JsonObject().apply {
            addProperty("id", deviceIdentity.deviceId)
            addProperty("publicKey", deviceIdentity.publicKeyBase64Url)
            addProperty(
                "signature",
                deviceIdentity.signAuthPayload(
                    clientId = CLIENT_ID,
                    clientMode = CLIENT_MODE,
                    role = ROLE,
                    scopes = SCOPES,
                    signedAtMs = signedAtMs,
                    token = token,
                    nonce = nonce,
                ),
            )
            addProperty("signedAt", signedAtMs)
            addProperty("nonce", nonce)
            deviceIdentity.deviceToken?.let { addProperty("deviceToken", it) }
        })
        addProperty("locale", "nl-NL")
        addProperty("userAgent", "clawsses-android/$appVersion")
    }
}
