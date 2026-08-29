package com.clawsses.shared

import com.google.gson.JsonObject
import com.google.gson.JsonParser

sealed interface GlassesCommand {
    data class UserInput(val text: String, val clientMessageId: String?) : GlassesCommand
    data object StartVoice : GlassesCommand
    data object CancelVoice : GlassesCommand
    data class ListSessions(val offset: Int) : GlassesCommand
    data class SwitchSession(val sessionKey: String) : GlassesCommand
    data object CreateSession : GlassesCommand
    data object ListAgents : GlassesCommand
    data class SwitchAgent(val agentId: String, val agentName: String?) : GlassesCommand
    data class ListModels(val offset: Int) : GlassesCommand
    data class SelectModel(val sessionKey: String, val catalog: String, val index: Int) : GlassesCommand
    data object AbortRun : GlassesCommand
    data class Slash(val command: String) : GlassesCommand
    data class RequestState(val versionCode: Int?) : GlassesCommand
    data class TtsToggle(val enabled: Boolean) : GlassesCommand
    data class TtsControl(val action: String) : GlassesCommand
    data class TalkModeToggle(val enabled: Boolean) : GlassesCommand
    data class LiveCaptionToggle(val enabled: Boolean) : GlassesCommand
    data class HudCardAction(val cardId: String, val actionId: String) : GlassesCommand
    data class TakePhoto(val sendAfterCapture: Boolean, val visionPrompt: String?) : GlassesCommand
    data class RemovePhoto(val all: Boolean, val index: Int?) : GlassesCommand
    data class RequestMoreHistory(val beforeMessageId: String?) : GlassesCommand
}

sealed interface GlassesCommandDecodeResult {
    data class Success(val command: GlassesCommand) : GlassesCommandDecodeResult
    data class UnknownType(val type: String?) : GlassesCommandDecodeResult
    data class Malformed(val type: String?, val reason: String) : GlassesCommandDecodeResult
}

object GlassesCommandCodec {
    fun decode(raw: String): GlassesCommandDecodeResult {
        val json = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (error: Exception) {
            return GlassesCommandDecodeResult.Malformed(null, error.message ?: "Invalid JSON")
        }
        val type = json.stringOrNull("type")
            ?: return GlassesCommandDecodeResult.Malformed(null, "Missing message type")
        return runCatching { decode(type, json) }
            .getOrElse { GlassesCommandDecodeResult.Malformed(type, it.message ?: "Invalid payload") }
    }

    private fun decode(type: String, json: JsonObject): GlassesCommandDecodeResult = when (type) {
        "user_input" -> success(
            GlassesCommand.UserInput(
                text = json.string("text"),
                clientMessageId = json.stringOrNull("id"),
            ),
        )
        "start_voice" -> success(GlassesCommand.StartVoice)
        "cancel_voice" -> success(GlassesCommand.CancelVoice)
        "list_sessions" -> success(GlassesCommand.ListSessions(json.intOrDefault("offset", 0)))
        "switch_session" -> success(GlassesCommand.SwitchSession(json.nonBlankString("sessionKey")))
        "create_session" -> success(GlassesCommand.CreateSession)
        "list_agents" -> success(GlassesCommand.ListAgents)
        "switch_agent" -> success(
            GlassesCommand.SwitchAgent(
                agentId = json.nonBlankString("agentId"),
                agentName = json.stringOrNull("agentName"),
            ),
        )
        "list_models" -> success(GlassesCommand.ListModels(json.intOrDefault("offset", -1)))
        "select_model" -> success(
            GlassesCommand.SelectModel(
                sessionKey = json.nonBlankString("sessionKey"),
                catalog = json.nonBlankString("catalog"),
                index = json.requiredInt("index"),
            ),
        )
        "abort_run" -> success(GlassesCommand.AbortRun)
        "slash_command" -> success(GlassesCommand.Slash(json.nonBlankString("command")))
        "request_state" -> success(GlassesCommand.RequestState(json.optionalInt("versionCode")))
        "tts_toggle" -> success(GlassesCommand.TtsToggle(json.requiredBoolean("enabled")))
        "tts_control" -> success(GlassesCommand.TtsControl(json.nonBlankString("action")))
        "talk_mode_toggle" -> success(GlassesCommand.TalkModeToggle(json.requiredBoolean("enabled")))
        "live_caption_toggle" -> success(GlassesCommand.LiveCaptionToggle(json.requiredBoolean("enabled")))
        "hud_card_action" -> success(
            GlassesCommand.HudCardAction(
                cardId = json.nonBlankString("cardId"),
                actionId = json.nonBlankString("actionId"),
            ),
        )
        "take_photo" -> success(
            GlassesCommand.TakePhoto(
                sendAfterCapture = json.booleanOrDefault("sendAfterCapture", false),
                visionPrompt = json.stringOrNull("visionPrompt"),
            ),
        )
        "remove_photo" -> success(
            GlassesCommand.RemovePhoto(
                all = json.booleanOrDefault("all", false),
                index = json.optionalInt("index"),
            ),
        )
        "request_more_history" -> success(
            GlassesCommand.RequestMoreHistory(json.stringOrNull("beforeMessageId")),
        )
        else -> GlassesCommandDecodeResult.UnknownType(type)
    }

    private fun success(command: GlassesCommand) = GlassesCommandDecodeResult.Success(command)

    private fun JsonObject.element(name: String) = get(name)?.takeUnless { it.isJsonNull }

    private fun JsonObject.string(name: String): String =
        element(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw IllegalArgumentException("$name must be a string")

    private fun JsonObject.nonBlankString(name: String): String =
        string(name).takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$name must not be blank")

    private fun JsonObject.stringOrNull(name: String): String? =
        element(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?.takeIf(String::isNotBlank)

    private fun JsonObject.requiredInt(name: String): Int =
        optionalInt(name) ?: throw IllegalArgumentException("$name must be an integer")

    private fun JsonObject.optionalInt(name: String): Int? =
        element(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonObject.intOrDefault(name: String, default: Int): Int =
        if (element(name) == null) default else requiredInt(name)

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        element(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            ?: throw IllegalArgumentException("$name must be a boolean")

    private fun JsonObject.booleanOrDefault(name: String, default: Boolean): Boolean =
        if (element(name) == null) default else requiredBoolean(name)
}
