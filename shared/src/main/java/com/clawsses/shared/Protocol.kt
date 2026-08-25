package com.clawsses.shared

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * Shared protocol definitions for communication between:
 * - Phone <-> OpenClaw Gateway (WebSocket)
 * - Phone <-> Glasses (BLE/CXR)
 */

private val gson = Gson()

// ============================================
// OpenClaw Gateway Protocol
// ============================================

/**
 * Request sent from client to OpenClaw Gateway.
 */
data class OpenClawRequest(
    @SerializedName("type") val type: String = "req",
    @SerializedName("id") val id: String,
    @SerializedName("method") val method: String,
    @SerializedName("params") val params: JsonObject? = null
) {
    fun toJson(): String = gson.toJson(this)
}

/**
 * Response from OpenClaw Gateway to client.
 */
data class OpenClawResponse(
    @SerializedName("type") val type: String = "res",
    @SerializedName("id") val id: String,
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("payload") val payload: JsonObject? = null,
    @SerializedName("error") val error: JsonObject? = null
) {
    companion object {
        fun fromJson(json: String): OpenClawResponse = gson.fromJson(json, OpenClawResponse::class.java)
    }
}

/**
 * Server-pushed event from OpenClaw Gateway.
 */
data class OpenClawEvent(
    @SerializedName("type") val type: String = "event",
    @SerializedName("event") val event: String,
    @SerializedName("payload") val payload: JsonObject? = null,
    @SerializedName("seq") val seq: Long? = null,
    @SerializedName("stateVersion") val stateVersion: Long? = null
) {
    companion object {
        fun fromJson(json: String): OpenClawEvent = gson.fromJson(json, OpenClawEvent::class.java)
    }
}

/** OpenClaw Gateway methods. */
object OpenClawMethods {
    const val CONNECT = "connect"
    const val CHAT_SEND = "chat.send"
    const val CHAT_ABORT = "chat.abort"
    const val CHANNEL_SEND = "channel.send"
    const val CHANNEL_LIST = "channel.list"
    const val SESSION_CREATE = "sessions.create"
    const val SESSION_RESET = "sessions.reset"
    const val SESSION_LIST = "sessions.list"
    const val SESSION_RUN = "session.run"
    const val CHAT_HISTORY = "chat.history"
    const val CONFIG_GET = "config.get"
    const val AGENTS_LIST = "agents.list"
    const val MODELS_LIST = "models.list"
    const val SESSION_MODEL_SELECT = "sessions.model.select"
    const val SYSTEM_PRESENCE = "system-presence"
}

/** OpenClaw Gateway event names. */
object OpenClawEvents {
    const val CONNECT_CHALLENGE = "connect.challenge"
    const val AGENT = "agent"
    const val CHAT = "chat"
    const val PRESENCE = "presence"
    const val HEARTBEAT = "heartbeat"
}

/**
 * Parse a raw WebSocket frame into the appropriate OpenClaw message type.
 * Returns null if the frame is not valid JSON or has no recognized type.
 */
fun parseOpenClawFrame(json: String): Any? {
    return try {
        val obj = JsonParser.parseString(json).asJsonObject
        when (obj.get("type")?.asString) {
            "res" -> gson.fromJson(obj, OpenClawResponse::class.java)
            "event" -> gson.fromJson(obj, OpenClawEvent::class.java)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

// ============================================
// Phone -> Glasses Messages
// ============================================

/**
 * A chat message to display on the glasses HUD.
 * Sent when a message is complete (user echo or finished assistant message).
 */
data class ChatMessage(
    @SerializedName("type") val type: String = "chat_message",
    @SerializedName("id") val id: String,
    @SerializedName("role") val role: String,  // "user" or "assistant"
    @SerializedName("content") val content: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("attachments") val attachments: List<ChatAttachment> = emptyList()
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): ChatMessage = gson.fromJson(json, ChatMessage::class.java)
    }
}

/** Embedded image attachment retained with a chat message. */
data class ChatAttachment(
    @SerializedName("type") val type: String = "image",
    @SerializedName("mimeType") val mimeType: String? = null,
    @SerializedName("fileName") val fileName: String? = null,
    @SerializedName("base64") val base64: String? = null
)

/**
 * Agent has acknowledged the request but no content yet.
 * Glasses should show a thinking/processing indicator.
 */
data class AgentThinking(
    @SerializedName("type") val type: String = "agent_thinking",
    @SerializedName("id") val id: String,
    @SerializedName("phase") val phase: String = "thinking",
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): AgentThinking = gson.fromJson(json, AgentThinking::class.java)
    }
}

/**
 * A privacy-filtered progress item for the glasses HUD.
 * Raw reasoning, tool arguments, tool results, and error payloads never cross CXR.
 */
data class AgentProgressUpdate(
    @SerializedName("type") val type: String = "agent_progress",
    @SerializedName("id") val id: String,
    @SerializedName("kind") val kind: String,
    @SerializedName("label") val label: String,
    @SerializedName("state") val state: String = "active",
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun clear(): AgentProgressUpdate = AgentProgressUpdate(
            id = "all",
            kind = "status",
            label = "",
            state = "clear",
        )
    }
}

/**
 * A streaming text chunk from the agent.
 * Glasses should append this to the message with the given id.
 */
data class ChatStream(
    @SerializedName("type") val type: String = "chat_stream",
    @SerializedName("id") val id: String,
    @SerializedName("role") val role: String = "assistant",
    @SerializedName("chunk") val chunk: String
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): ChatStream = gson.fromJson(json, ChatStream::class.java)
    }
}

/**
 * Streaming is complete for the given message.
 * Glasses should remove the streaming cursor and mark the message as final.
 */
data class ChatStreamEnd(
    @SerializedName("type") val type: String = "chat_stream_end",
    @SerializedName("id") val id: String,
    @SerializedName("state") val state: String = "final"
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): ChatStreamEnd = gson.fromJson(json, ChatStreamEnd::class.java)
    }
}

/** Active OpenClaw run state mirrored from the phone to the glasses. */
data class RunStateUpdate(
    @SerializedName("type") val type: String = "run_state",
    @SerializedName("state") val state: String,
    @SerializedName("canAbort") val canAbort: Boolean,
    @SerializedName("error") val error: String? = null
) {
    fun toJson(): String = gson.toJson(this)
}

data class TalkModeStateUpdate(
    @SerializedName("type") val type: String = "talk_mode_state",
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("phase") val phase: String,
    @SerializedName("interruptible") val interruptible: Boolean,
    @SerializedName("error") val error: String? = null
) {
    fun toJson(): String = gson.toJson(this)
}

/**
 * OpenClaw connection state update.
 */
data class ConnectionUpdate(
    @SerializedName("type") val type: String = "connection_update",
    @SerializedName("connected") val connected: Boolean,
    @SerializedName("sessionId") val sessionId: String? = null,
    @SerializedName("sessionName") val sessionName: String? = null
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): ConnectionUpdate = gson.fromJson(json, ConnectionUpdate::class.java)
    }
}

/** Valid range and fallback for one glasses page-navigation gesture. */
object ScrollSettings {
    const val MIN_MESSAGES_PER_STEP = 1
    const val MAX_MESSAGES_PER_STEP = 5
    const val DEFAULT_MESSAGES_PER_STEP = 1

    fun normalizeMessagesPerStep(value: Int): Int =
        value.coerceIn(MIN_MESSAGES_PER_STEP, MAX_MESSAGES_PER_STEP)
}

/** Hard limit imposed by the Rokid CXR custom-command transport. */
object CxrPayloadLimits {
    const val MAX_BYTES = 500

    fun byteSize(payload: String): Int = payload.toByteArray(Charsets.UTF_8).size

    fun fits(payload: String): Boolean = byteSize(payload) <= MAX_BYTES
}

/** End-to-end acknowledgment for reliable phone-to-glasses transport messages. */
data class TransportAck(
    @SerializedName("type") val type: String = "transport_ack",
    @SerializedName("tx") val transactionId: String,
) {
    fun toJson(): String = gson.toJson(this)
}

/** Compact, bounded pages keep session messages valid on the CXR command channel. */
object SessionPaging {
    const val PAGE_SIZE = 3
    const val MAX_DISPLAY_NAME_CHARS = 36

    fun compactName(name: String): String = when {
        name.length <= MAX_DISPLAY_NAME_CHARS -> name
        else -> name.take(MAX_DISPLAY_NAME_CHARS - 3) + "..."
    }
}

/**
 * Phone-controlled page step sent to the glasses HUD. The serialized field
 * keeps its legacy name so a Build 32 phone can update older HUD builds safely.
 */
data class ScrollSettingsUpdate(
    @SerializedName("type") val type: String = "scroll_settings",
    @SerializedName("messagesPerStep") val messagesPerStep: Int = ScrollSettings.DEFAULT_MESSAGES_PER_STEP,
) {
    fun toJson(): String = gson.toJson(this)
}

/**
 * List of available sessions from OpenClaw.
 */
data class SessionListUpdate(
    @SerializedName("type") val type: String = "session_list",
    @SerializedName("sessions") val sessions: List<SessionPageItem>,
    @SerializedName("offset") val offset: Int = 0,
    @SerializedName("nextOffset") val nextOffset: Int? = null,
    @SerializedName("hasMore") val hasMore: Boolean = false,
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): SessionListUpdate = gson.fromJson(json, SessionListUpdate::class.java)
    }
}

/** Minimal session representation sent over the size-constrained CXR channel. */
data class SessionPageItem(
    @SerializedName("k") val key: String,
    @SerializedName("n") val name: String,
    @SerializedName("u") val hasUnread: Boolean? = null,
)

/** Progress or failure feedback for a glasses-initiated session operation. */
data class SessionOperationUpdate(
    @SerializedName("type") val type: String = "session_operation",
    @SerializedName("operation") val operation: String,
    @SerializedName("state") val state: String,
    @SerializedName("error") val error: String? = null
) {
    fun toJson(): String = gson.toJson(this)
}

data class SessionInfo(
    @SerializedName("key") val key: String,
    @SerializedName("displayName") val displayName: String? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("derivedTitle") val derivedTitle: String? = null,
    @SerializedName("updatedAt") val updatedAt: Long? = null,
    @SerializedName("kind") val kind: String? = null
) {
    /** Best available display name for this session */
    val name: String get() = label ?: displayName ?: derivedTitle ?: key
}

/** Available OpenClaw agents and the agent selected by the active session. */
data class AgentListUpdate(
    @SerializedName("type") val type: String = "agent_list",
    @SerializedName("agents") val agents: List<AgentInfo>,
    @SerializedName("currentAgentId") val currentAgentId: String? = null
) {
    fun toJson(): String = gson.toJson(this)
}

data class AgentInfo(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("model") val model: String? = null
)

/** One configured model exposed by the OpenClaw gateway. */
data class ModelInfo(
    @SerializedName("ref") val ref: String,
    @SerializedName("provider") val provider: String,
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("available") val available: Boolean = true,
)

/** Fixed, bounded model pages for the Rokid picker. */
object ModelPaging {
    const val PAGE_SIZE = 3
    const val MAX_DISPLAY_NAME_CHARS = 30
    const val MAX_PROVIDER_CHARS = 16

    fun compactName(name: String): String = compact(name, MAX_DISPLAY_NAME_CHARS)
    fun compactProvider(provider: String): String = compact(provider, MAX_PROVIDER_CHARS)

    private fun compact(value: String, limit: Int): String = when {
        value.length <= limit -> value
        else -> value.take(limit - 3) + "..."
    }
}

/** Compact model row; the opaque catalog token and global index resolve phone-side. */
data class ModelPageItem(
    @SerializedName("i") val index: Int,
    @SerializedName("n") val name: String,
    @SerializedName("p") val provider: String,
    @SerializedName("a") val available: Boolean,
)

/** One fixed Rokid model-picker page, kept below the CXR command limit. */
data class ModelPageUpdate(
    @SerializedName("type") val type: String = "model_page",
    @SerializedName("c") val catalogId: String,
    @SerializedName("m") val models: List<ModelPageItem>,
    @SerializedName("o") val offset: Int,
    @SerializedName("x") val nextOffset: Int? = null,
    @SerializedName("pi") val pageIndex: Int,
    @SerializedName("pc") val pageCount: Int,
    @SerializedName("ci") val currentIndex: Int? = null,
    @SerializedName("e") val error: String? = null,
) {
    fun toJson(): String = gson.toJson(this)
}

/** Progress or result for a glasses-initiated session-model selection. */
data class ModelOperationUpdate(
    @SerializedName("type") val type: String = "model_operation",
    @SerializedName("state") val state: String,
    @SerializedName("ci") val currentIndex: Int? = null,
    @SerializedName("n") val currentName: String? = null,
    @SerializedName("error") val error: String? = null,
) {
    fun toJson(): String = gson.toJson(this)
}

// ============================================
// Glasses -> Phone Messages
// ============================================

/**
 * Requests the current phone/OpenClaw state and identifies the running glasses build.
 * Version fields are nullable so phones remain compatible with older glasses builds.
 */
data class GlassesStateRequest(
    @SerializedName("type") val type: String = "request_state",
    @SerializedName("versionName") val versionName: String? = null,
    @SerializedName("versionCode") val versionCode: Int? = null,
) {
    fun toJson(): String = gson.toJson(this)
}

/**
 * User input from glasses (text and optional photo).
 */
data class UserInput(
    @SerializedName("type") val type: String = "user_input",
    @SerializedName("text") val text: String,
    @SerializedName("imageBase64") val imageBase64: String? = null
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): UserInput = gson.fromJson(json, UserInput::class.java)
    }
}

/**
 * Session management action from glasses.
 */
data class SessionAction(
    @SerializedName("type") val type: String,  // "list_sessions" or "switch_session"
    @SerializedName("sessionKey") val sessionKey: String? = null,
    @SerializedName("offset") val offset: Int? = null,
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): SessionAction = gson.fromJson(json, SessionAction::class.java)
    }
}

/**
 * Slash command from glasses (e.g. "/model", "/clear").
 */
data class SlashCommand(
    @SerializedName("type") val type: String = "slash_command",
    @SerializedName("command") val command: String
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): SlashCommand = gson.fromJson(json, SlashCommand::class.java)
    }
}

/**
 * Request for more chat history from glasses.
 * Phone should load more history and send back a history_prepend message.
 * @param beforeMessageId The ID of the oldest currently-displayed message.
 *                        Phone uses this to know what messages glasses already have.
 */
data class RequestMoreHistory(
    @SerializedName("type") val type: String = "request_more_history",
    @SerializedName("beforeMessageId") val beforeMessageId: String? = null
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): RequestMoreHistory = gson.fromJson(json, RequestMoreHistory::class.java)
    }
}

// ============================================
// Wake Signal Protocol (Phone <-> Glasses)
// ============================================

/**
 * Wake signal sent from phone to glasses to wake the display.
 * Phone sends this before sending content when glasses may be in standby.
 *
 * The wake mechanism works as follows:
 * 1. Phone detects new streaming content or spontaneous messages
 * 2. Phone sends wake_signal with reason and buffered message count
 * 3. Glasses receives via CXR bridge (which stays active even in standby)
 * 4. Glasses wakes display and sends wake_ack confirming readiness
 * 5. Phone delivers buffered messages after receiving ack
 *
 * @param reason The reason for the wake signal (stream_content, new_message, cron_message)
 * @param bufferedCount Number of messages buffered and waiting to be delivered
 * @param messageId Optional ID of the message that triggered the wake (for correlation)
 */
data class WakeSignal(
    @SerializedName("type") val type: String = "wake_signal",
    @SerializedName("reason") val reason: String,
    @SerializedName("bufferedCount") val bufferedCount: Int = 0,
    @SerializedName("messageId") val messageId: String? = null,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        const val REASON_STREAM_CONTENT = "stream_content"
        const val REASON_NEW_MESSAGE = "new_message"
        const val REASON_CRON_MESSAGE = "cron_message"

        fun fromJson(json: String): WakeSignal = gson.fromJson(json, WakeSignal::class.java)
    }
}

/**
 * Acknowledgment from glasses that it has woken and is ready to receive messages.
 * Phone should deliver buffered messages after receiving this.
 *
 * @param ready True if glasses is awake and ready, false if wake failed
 * @param timestamp When the glasses acknowledged the wake signal
 */
data class WakeAck(
    @SerializedName("type") val type: String = "wake_ack",
    @SerializedName("ready") val ready: Boolean = true,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): WakeAck = gson.fromJson(json, WakeAck::class.java)
    }
}

// ============================================
// TTS State Protocol (Phone <-> Glasses)
// ============================================

/**
 * TTS toggle request from glasses to phone.
 * Glasses sends this when user toggles voice responses in the More menu.
 */
data class TtsToggle(
    @SerializedName("type") val type: String = "tts_toggle",
    @SerializedName("enabled") val enabled: Boolean
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): TtsToggle = gson.fromJson(json, TtsToggle::class.java)
    }
}

/**
 * TTS state update from phone to glasses.
 * Phone sends this when TTS settings change or on connection.
 */
data class TtsState(
    @SerializedName("type") val type: String = "tts_state",
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("voiceName") val voiceName: String? = null,
    @SerializedName("provider") val provider: String? = null,
    @SerializedName("playbackState") val playbackState: String = "idle",
    @SerializedName("canReplay") val canReplay: Boolean = false,
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        fun fromJson(json: String): TtsState = gson.fromJson(json, TtsState::class.java)
    }
}

/** Stop or replay speech without changing the enabled preference. */
data class TtsControl(
    @SerializedName("type") val type: String = "tts_control",
    @SerializedName("action") val action: String,
) {
    fun toJson(): String = gson.toJson(this)
}

// ============================================
// Ambient HUD Protocol (Phone <-> Glasses)
// ============================================

data class HudCardAction(
    @SerializedName("id") val id: String,
    @SerializedName("label") val label: String,
)

/** A short, actionable card that may be shown without opening the chat. */
data class HudCard(
    @SerializedName("type") val type: String = "hud_card",
    @SerializedName("id") val id: String,
    @SerializedName("source") val source: String,
    @SerializedName("title") val title: String,
    @SerializedName("body") val body: String,
    @SerializedName("priority") val priority: String = "normal",
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("expiresAt") val expiresAt: Long? = null,
    @SerializedName("actions") val actions: List<HudCardAction> = emptyList(),
) {
    fun toJson(): String = gson.toJson(this)
}

data class HudCardActionRequest(
    @SerializedName("type") val type: String = "hud_card_action",
    @SerializedName("cardId") val cardId: String,
    @SerializedName("actionId") val actionId: String,
) {
    fun toJson(): String = gson.toJson(this)
}

/** Live microphone caption state. Translation is optional and never replaces source text. */
data class LiveCaptionUpdate(
    @SerializedName("type") val type: String = "live_caption",
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("sourceText") val sourceText: String = "",
    @SerializedName("translatedText") val translatedText: String? = null,
    @SerializedName("sourceLanguage") val sourceLanguage: String? = null,
    @SerializedName("targetLanguage") val targetLanguage: String? = null,
    @SerializedName("error") val error: String? = null,
) {
    fun toJson(): String = gson.toJson(this)
}

/** Canonical camera commands understood by phone and glasses voice paths. */
object VisionCommands {
    val phrases = setOf(
        "read this",
        "lies das",
        "translate this",
        "übersetze das",
        "identify this",
        "erkenne das",
        "remember this",
        "merk dir das",
    )

    fun promptFor(command: String): String? = when (command.trim().lowercase()) {
        "read this", "lies das" ->
            "Read all visible text in this photo. Preserve important structure and summarize only if needed."
        "translate this", "übersetze das" ->
            "Read all visible text in this photo and translate it into the user's preferred language. Show the translation first."
        "identify this", "erkenne das" ->
            "Identify the main object, place, product, or situation in this photo and provide the most useful concise context."
        "remember this", "merk dir das" ->
            "Create and save a concise memory note about the important thing shown in this photo. State what you remembered."
        else -> null
    }
}

// ============================================
// Utility
// ============================================

/**
 * Extract the "type" field from a JSON message string.
 */
fun extractMessageType(json: String): String? {
    return try {
        JsonParser.parseString(json).asJsonObject.get("type")?.asString
    } catch (e: Exception) {
        null
    }
}
