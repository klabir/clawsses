package com.clawsses.phone.openclaw

import android.util.Log
import com.clawsses.shared.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket client for connecting to an OpenClaw Gateway.
 * Handles the connect handshake (with Ed25519 device identity),
 * request/response correlation, event streaming, and auto-reconnect.
 */
class OpenClawClient(
    private val deviceIdentity: DeviceIdentity
) {

    companion object {
        private const val TAG = "OpenClawClient"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val PROTOCOL_VERSION = 4
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Authenticating : ConnectionState()
        object Connected : ConnectionState()
        data class PairingRequired(val message: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    enum class RunState {
        IDLE,
        WAITING,
        REASONING,
        STREAMING,
        ABORTING,
        ERROR
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _events = MutableSharedFlow<OpenClawEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<OpenClawEvent> = _events.asSharedFlow()

    private val _runState = MutableStateFlow(RunState.IDLE)
    val runState: StateFlow<RunState> = _runState.asStateFlow()

    private val _runError = MutableStateFlow<String?>(null)
    val runError: StateFlow<String?> = _runError.asStateFlow()

    // Callbacks for forwarding to glasses
    var onChatMessage: ((ChatMessage) -> Unit)? = null
    var onChatHistory: ((List<ChatMessage>) -> Unit)? = null
    var onAgentThinking: ((AgentThinking) -> Unit)? = null
    var onChatStream: ((ChatStream) -> Unit)? = null
    var onChatStreamEnd: ((ChatStreamEnd) -> Unit)? = null
    var onSessionList: ((SessionListUpdate) -> Unit)? = null
    var onSessionOperation: ((SessionOperationUpdate) -> Unit)? = null
    var onAgentList: ((AgentListUpdate) -> Unit)? = null
    var onConnectionUpdate: ((ConnectionUpdate) -> Unit)? = null
    /** Fired after loadMoreHistory completes. Args: (prependedCount, hasMore) */
    var onMoreHistoryLoaded: ((Int, Boolean) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val requestSeq = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<OpenClawResponse>>()

    // Connection params (saved for reconnect)
    private var host: String = ""
    private var port: Int = 18789
    private var token: String = ""
    private var shouldReconnect = false

    // Active agent run tracking
    @Volatile private var activeRunId: String? = null
    @Volatile private var activeMessageId: String? = null
    @Volatile private var activeSessionKey: String? = null // session that initiated the current run
    @Volatile private var abortingRunId: String? = null
    private val completedAbortedRuns = ConcurrentHashMap<String, Long>()
    private var streamingContent = StringBuilder()
    private var lastAgentPhase: String? = null

    // Current session tracking (exposed as StateFlow for phone UI)
    private val _currentSessionKey = MutableStateFlow<String?>(null)
    val currentSessionKey: StateFlow<String?> = _currentSessionKey.asStateFlow()

    // History pagination: tracks how many messages were last requested from OpenClaw
    private var currentHistoryLimit = 50
    private val _isLoadingMoreHistory = MutableStateFlow(false)
    val isLoadingMoreHistory: StateFlow<Boolean> = _isLoadingMoreHistory.asStateFlow()

    // Sessions with unread messages (received while that session was not active)
    private val _unreadSessions = MutableStateFlow<Set<String>>(emptySet())
    val unreadSessions: StateFlow<Set<String>> = _unreadSessions.asStateFlow()

    // Available sessions (exposed as StateFlow for phone UI)
    private val _sessionList = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessionList: StateFlow<List<SessionInfo>> = _sessionList.asStateFlow()

    private val _agentList = MutableStateFlow<List<AgentInfo>>(emptyList())
    val agentList: StateFlow<List<AgentInfo>> = _agentList.asStateFlow()

    // Challenge nonce for auth handshake
    private var challengeNonce: String? = null

    fun connect(host: String, port: Int, token: String) {
        val normalizedHost = host.trim().trimEnd('/')
        if (normalizedHost.startsWith("ws://") || normalizedHost.startsWith("http://")) {
            shouldReconnect = false
            _connectionState.value = ConnectionState.Error("Secure WSS connection required")
            return
        }

        this.host = normalizedHost
        this.port = port
        this.token = token
        this.shouldReconnect = true

        // Build a TLS-only WebSocket URL.
        val url = when {
            normalizedHost.startsWith("wss://") -> {
                // User provided full URL - append port if not already in URL
                if (normalizedHost.contains(Regex(":\\d+$"))) normalizedHost else "$normalizedHost:$port"
            }
            else -> "wss://$normalizedHost:$port"
        }
        val originHost = normalizedHost
            .removePrefix("wss://")
            .trimEnd('/')
        val originUrl = "https://$originHost" + (if (originHost.contains(Regex(":\\d+$"))) "" else ":$port")

        Log.i(TAG, "Connecting to OpenClaw Gateway over WSS")
        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url(url)
            .header("Origin", originUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected (HTTP ${response.code})")
                _connectionState.value = ConnectionState.Authenticating
                // Wait for connect.challenge event from server
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleFrame(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing (code=$code)")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed (code=$code)")
                _connectionState.value = ConnectionState.Disconnected
                notifyConnectionUpdate(false)
                if (shouldReconnect) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failed: ${t.javaClass.simpleName}")
                _connectionState.value = ConnectionState.Error("${t.javaClass.simpleName}: ${t.message}")
                notifyConnectionUpdate(false)
                failAllPending("Connection lost")
                if (shouldReconnect) scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        shouldReconnect = false
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
        notifyConnectionUpdate(false)
        failAllPending("Disconnected")
    }

    /**
     * Send a user message to OpenClaw and trigger an agent run.
     */
    fun sendMessage(text: String, images: List<String>? = null) {
        val previousState = _runState.value
        if (previousState !in setOf(RunState.IDLE, RunState.ERROR) ||
            !_runState.compareAndSet(previousState, RunState.WAITING)) {
            Log.w(TAG, "Ignoring send while another agent run is active")
            return
        }
        _runError.value = null
        scope.launch {
            try {
                // Add user message to local chat
                val userMsgId = UUID.randomUUID().toString()
                val localAttachments = images.orEmpty().map { base64 ->
                    ChatAttachment(
                        mimeType = detectImageMimeType(base64),
                        base64 = base64
                    )
                }
                val userMsg = ChatMessage(
                    id = userMsgId,
                    role = "user",
                    content = text,
                    attachments = localAttachments
                )
                addChatMessage(userMsg)
                onChatMessage?.invoke(userMsg)

                // Send to OpenClaw as chat.send
                val idempotencyKey = UUID.randomUUID().toString()
                val sessionKey = _currentSessionKey.value ?: "main"
                val params = JsonObject().apply {
                    addProperty("sessionKey", sessionKey)
                    addProperty("idempotencyKey", idempotencyKey)
                    addProperty("message", text)
                    if (!images.isNullOrEmpty()) {
                        val attachments = JsonArray()
                        images.forEachIndexed { i, base64 ->
                            val mimeType = detectImageMimeType(base64)
                            val ext = if (mimeType == "image/webp") "webp" else "jpg"
                            attachments.add(JsonObject().apply {
                                addProperty("type", "image")
                                addProperty("mimeType", mimeType)
                                addProperty("fileName", "glasses-photo-${i + 1}.$ext")
                                addProperty("content", base64)
                            })
                        }
                        add("attachments", attachments)
                    }
                }

                val assistantMsgId = UUID.randomUUID().toString()
                activeMessageId = assistantMsgId
                activeSessionKey = sessionKey
                activeRunId = idempotencyKey
                abortingRunId = null
                streamingContent.clear()
                lastAgentPhase = null

                val response = sendRequest(OpenClawMethods.CHAT_SEND, params)
                if (response.ok) {
                    if (activeRunId != idempotencyKey || completedAbortedRuns.containsKey(idempotencyKey)) {
                        return@launch
                    }
                    // OpenClaw uses the idempotency key as runId. Honor the echoed value
                    // if present while retaining pre-ACK cancellation support.
                    activeRunId = response.payload?.get("runId")
                        ?.takeIf { it.isJsonPrimitive }?.asString ?: idempotencyKey
                    Log.d(TAG, "Agent run started")
                    // Notify glasses that agent is thinking
                    notifyAgentPhase("thinking", onlyIfUnset = true)
                } else {
                    val errorMsg = response.error?.get("message")?.asString ?: "Agent run failed"
                    Log.e(TAG, "Agent run failed")
                    finishRunWithoutContent("error", errorMsg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message", e)
                finishRunWithoutContent("error", "Unable to start agent run")
            }
        }
    }

    /** Abort the exact active run without broad session cancellation. */
    fun abortActiveRun() {
        val frozenRunId = activeRunId ?: return
        val frozenSessionKey = activeSessionKey ?: return
        if (_runState.value == RunState.ABORTING) return

        abortingRunId = frozenRunId
        updateRunState(RunState.ABORTING)
        notifyAgentPhase("aborting")

        scope.launch {
            val params = JsonObject().apply {
                addProperty("sessionKey", frozenSessionKey)
                addProperty("runId", frozenRunId)
            }
            try {
                val response = sendRequest(OpenClawMethods.CHAT_ABORT, params)
                if (response.ok) {
                    rememberAbortedRun(frozenRunId)
                    if (activeRunId == frozenRunId) finalizeStreaming("aborted")
                } else {
                    abortingRunId = null
                    updateRunState(
                        if (streamingContent.isNotEmpty()) RunState.STREAMING else RunState.WAITING,
                        response.error?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: "Run could not be stopped"
                    )
                }
            } catch (e: TimeoutCancellationException) {
                // The server may have accepted the request even if its ACK was lost.
                // Stop local rendering after the bounded request timeout and ignore late events.
                rememberAbortedRun(frozenRunId)
                if (activeRunId == frozenRunId) finalizeStreaming("aborted")
                Log.w(TAG, "Run abort timed out; local run closed")
            } catch (e: Exception) {
                abortingRunId = null
                updateRunState(
                    if (streamingContent.isNotEmpty()) RunState.STREAMING else RunState.WAITING,
                    "Run could not be stopped"
                )
                Log.e(TAG, "Run abort failed", e)
            }
        }
    }

    /**
     * Request the list of available sessions from the OpenClaw gateway.
     * The server returns GatewaySessionRow objects with key, displayName, label,
     * derivedTitle, updatedAt, kind, etc.
     */
    fun requestSessions(reportOperation: Boolean = false) {
        if (reportOperation) {
            onSessionOperation?.invoke(
                SessionOperationUpdate(operation = "list", state = "loading")
            )
        }
        scope.launch {
            try {
                val params = JsonObject().apply {
                    addProperty("includeDerivedTitles", true)
                }
                val response = sendRequest(OpenClawMethods.SESSION_LIST, params)
                if (response.ok) {
                    val sessionsPayload = response.payload
                    val sessions = mutableListOf<SessionInfo>()
                    val sessionsArray = sessionsPayload?.getAsJsonArray("sessions")
                    sessionsArray?.forEach { element ->
                        val obj = element.asJsonObject
                        sessions.add(SessionInfo(
                            key = obj.get("key")?.asString ?: "",
                            displayName = obj.get("displayName")?.asString,
                            label = obj.get("label")?.asString,
                            derivedTitle = obj.get("derivedTitle")?.asString,
                            updatedAt = obj.get("updatedAt")?.asLong,
                            kind = obj.get("kind")?.asString
                        ))
                    }
                    _sessionList.value = sessions
                    onSessionList?.invoke(SessionListUpdate(
                        sessions = sessions,
                        currentSessionKey = _currentSessionKey.value,
                        unreadSessionKeys = _unreadSessions.value.toList()
                    ))
                } else {
                    Log.e(TAG, "Session list request failed")
                    if (reportOperation) {
                        onSessionOperation?.invoke(
                            SessionOperationUpdate(
                                operation = "list",
                                state = "error",
                                error = responseErrorMessage(response, "Could not load sessions")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting sessions", e)
                if (reportOperation) {
                    onSessionOperation?.invoke(
                        SessionOperationUpdate(
                            operation = "list",
                            state = "error",
                            error = "Could not load sessions"
                        )
                    )
                }
            }
        }
    }

    /** Request the read-only agent roster exposed by the gateway. */
    fun requestAgents() {
        scope.launch {
            try {
                val response = sendRequest(OpenClawMethods.AGENTS_LIST, JsonObject())
                if (!response.ok) {
                    Log.e(TAG, "Agent list request failed")
                    return@launch
                }

                val agents = response.payload?.getAsJsonArray("agents")
                    ?.mapNotNull { element ->
                        val obj = element.takeIf { it.isJsonObject }?.asJsonObject
                            ?: return@mapNotNull null
                        val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                            ?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val identity = obj.get("identity")?.takeIf { it.isJsonObject }?.asJsonObject
                        val identityName = identity?.get("name")
                            ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                        val configuredName = obj.get("name")
                            ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                        val emoji = identity?.get("emoji")
                            ?.takeIf { it.isJsonPrimitive }?.asString
                            ?.takeIf { it.isNotBlank() && it != "(not set)" }
                        var name = configuredName ?: identityName ?: id
                        if (emoji != null && !name.contains(emoji)) name = "$emoji $name"
                        val modelElement = obj.get("model")
                        val model = when {
                            modelElement == null -> null
                            modelElement.isJsonPrimitive -> modelElement.asString
                            modelElement.isJsonObject -> modelElement.asJsonObject.get("primary")
                                ?.takeIf { it.isJsonPrimitive }?.asString
                            else -> null
                        }
                        AgentInfo(id = id, name = name, model = model)
                    }
                    .orEmpty()

                _agentList.value = agents
                onAgentList?.invoke(
                    AgentListUpdate(
                        agents = agents,
                        currentAgentId = agentIdFromSessionKey(_currentSessionKey.value)
                            ?: response.payload?.get("defaultId")
                                ?.takeIf { it.isJsonPrimitive }?.asString
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting agents", e)
            }
        }
    }

    /** Select an agent's canonical main session, which is created lazily if needed. */
    fun switchAgent(agentId: String, displayName: String? = null) {
        val normalizedId = agentId.trim()
        if (normalizedId.isEmpty() || ':' in normalizedId) return

        scope.launch {
            val key = "agent:$normalizedId:main"
            _currentSessionKey.value = key
            _chatMessages.value = emptyList()
            currentHistoryLimit = 50
            _unreadSessions.value = _unreadSessions.value - key
            onConnectionUpdate?.invoke(
                ConnectionUpdate(
                    connected = true,
                    sessionId = key,
                    sessionName = displayName ?: _agentList.value.firstOrNull { it.id == normalizedId }?.name
                        ?: normalizedId
                )
            )
            loadSessionHistory(key)
        }
    }

    fun agentIdFromSessionKey(sessionKey: String?): String? {
        return SessionRequestFactory.agentIdFromSessionKey(sessionKey)
    }

    /**
     * Create a distinct root session for the active agent.
     * Supplying only agentId keeps this operation within operator.write scope.
     */
    fun createSession() {
        onSessionOperation?.invoke(
            SessionOperationUpdate(operation = "create", state = "loading")
        )
        scope.launch {
            try {
                Log.d(TAG, "Creating new session")
                val response = sendRequest(
                    OpenClawMethods.SESSION_CREATE,
                    SessionRequestFactory.createParams(_currentSessionKey.value)
                )
                if (response.ok) {
                    val newKey = response.payload?.get("key")?.asString
                    if (newKey.isNullOrBlank()) {
                        Log.e(TAG, "Session create returned no key")
                        onSessionOperation?.invoke(
                            SessionOperationUpdate(
                                operation = "create",
                                state = "error",
                                error = "Session was created without a key"
                            )
                        )
                        return@launch
                    }
                    Log.i(TAG, "Session creation completed")
                    _currentSessionKey.value = newKey
                    _chatMessages.value = emptyList()
                    currentHistoryLimit = 50
                    _unreadSessions.value = _unreadSessions.value - newKey
                    notifyConnectionUpdate(true, newKey)
                    onChatHistory?.invoke(emptyList())
                    onSessionOperation?.invoke(
                        SessionOperationUpdate(operation = "create", state = "success")
                    )
                    requestSessions()
                } else {
                    Log.e(TAG, "Session creation failed")
                    onSessionOperation?.invoke(
                        SessionOperationUpdate(
                            operation = "create",
                            state = "error",
                            error = responseErrorMessage(response, "Could not create session")
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating session", e)
                onSessionOperation?.invoke(
                    SessionOperationUpdate(
                        operation = "create",
                        state = "error",
                        error = "Could not create session"
                    )
                )
            }
        }
    }

    private fun responseErrorMessage(response: OpenClawResponse, fallback: String): String {
        return response.error
            ?.get("message")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
            ?.take(160)
            ?: fallback
    }

    /**
     * Switch to a different session by key.
     */
    fun switchSession(sessionKey: String) {
        scope.launch {
            Log.d(TAG, "Switching session")
            _currentSessionKey.value = sessionKey
            _chatMessages.value = emptyList()
            currentHistoryLimit = 50
            // Clear unread flag for the session we're switching to
            _unreadSessions.value = _unreadSessions.value - sessionKey
            notifyConnectionUpdate(true, sessionKey)
            loadSessionHistory(sessionKey)
        }
    }

    /**
     * Load chat history for the current (or given) session from the gateway.
     * Fetches messages via chat.history, populates local chat, and forwards to glasses.
     * Always notifies glasses (even for empty history) so they can clear stale messages.
     */
    fun loadSessionHistory(sessionKey: String? = null) {
        scope.launch {
            val key = sessionKey ?: _currentSessionKey.value ?: "main"
            try {
                val params = JsonObject().apply {
                    addProperty("sessionKey", key)
                    addProperty("limit", 50)
                }
                Log.d(TAG, "Requesting chat history for session $key")
                val response = sendRequest(OpenClawMethods.CHAT_HISTORY, params)
                if (response.ok) {
                    val chatMessages = mutableListOf<ChatMessage>()
                    val messagesArray = response.payload?.getAsJsonArray("messages")
                    Log.d(TAG, "Chat history response: payload keys=${response.payload?.keySet()}, messages count=${messagesArray?.size() ?: "null"}")

                    if (messagesArray != null && messagesArray.size() > 0) {
                        for (element in messagesArray) {
                            try {
                                val msgObj = element.asJsonObject
                                val role = msgObj.get("role")?.asString ?: continue
                                // Only show user and assistant messages
                                if (role != "user" && role != "assistant") continue

                                // content can be either a string or an array of {type,text} blocks
                                val contentElement = msgObj.get("content")
                                var content = ""
                                var attachments: List<ChatAttachment> = emptyList()
                                when {
                                    contentElement == null -> continue
                                    contentElement.isJsonPrimitive -> content = contentElement.asString
                                    contentElement.isJsonArray -> {
                                        val parsed = parseContentArray(contentElement.asJsonArray)
                                        content = parsed.first
                                        attachments = parsed.second
                                    }
                                    else -> continue
                                }
                                if (content.isEmpty() && attachments.isEmpty()) continue

                                val id = UUID.randomUUID().toString()
                                val timestamp = msgObj.get("timestamp")?.asLong ?: System.currentTimeMillis()
                                chatMessages.add(ChatMessage(
                                    id = id,
                                    role = role,
                                    content = content,
                                    timestamp = timestamp,
                                    attachments = attachments
                                ))
                            } catch (e: Exception) {
                                Log.w(TAG, "Skipping unparseable history message", e)
                            }
                        }
                    }

                    Log.d(TAG, "Loaded ${chatMessages.size} history messages for session $key")
                    _chatMessages.value = chatMessages
                    onChatHistory?.invoke(chatMessages)
                } else {
                    Log.e(TAG, "Chat history request failed: ${response.error}")
                    // Still notify with empty list so glasses clear stale messages
                    _chatMessages.value = emptyList()
                    onChatHistory?.invoke(emptyList())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading session history for $key", e)
                // Still notify with empty list so glasses clear stale messages
                _chatMessages.value = emptyList()
                onChatHistory?.invoke(emptyList())
            }
        }
    }

    /**
     * Load more chat history beyond what's currently cached.
     *
     * OpenClaw's chat.history doesn't support cursor pagination — it returns
     * the most recent N messages. So we increase N and re-fetch, then prepend
     * only the newly-discovered older messages to the existing list (keeping
     * existing message IDs stable).
     */
    fun loadMoreHistory() {
        if (_isLoadingMoreHistory.value) return
        _isLoadingMoreHistory.value = true

        scope.launch {
            val key = _currentSessionKey.value ?: "main"
            val existingMessages = _chatMessages.value
            val oldCount = existingMessages.size

            try {
                // Bump the limit by 50, cap at 500 (OpenClaw hard max is 1000)
                currentHistoryLimit = (currentHistoryLimit + 50).coerceAtMost(500)
                val params = JsonObject().apply {
                    addProperty("sessionKey", key)
                    addProperty("limit", currentHistoryLimit)
                }
                Log.d(TAG, "Requesting more history for session $key (limit=$currentHistoryLimit, existing=$oldCount)")
                val response = sendRequest(OpenClawMethods.CHAT_HISTORY, params)

                if (response.ok) {
                    val rawMessages = mutableListOf<ChatMessage>()
                    val messagesArray = response.payload?.getAsJsonArray("messages")
                    // Track total raw count (including system messages) for hasMore check
                    val totalReturnedByGateway = messagesArray?.size() ?: 0

                    if (messagesArray != null && messagesArray.size() > 0) {
                        for (element in messagesArray) {
                            try {
                                val msgObj = element.asJsonObject
                                val role = msgObj.get("role")?.asString ?: continue
                                if (role != "user" && role != "assistant") continue

                                val contentElement = msgObj.get("content")
                                var content = ""
                                var attachments: List<ChatAttachment> = emptyList()
                                when {
                                    contentElement == null -> continue
                                    contentElement.isJsonPrimitive -> content = contentElement.asString
                                    contentElement.isJsonArray -> {
                                        val parsed = parseContentArray(contentElement.asJsonArray)
                                        content = parsed.first
                                        attachments = parsed.second
                                    }
                                    else -> continue
                                }
                                if (content.isEmpty() && attachments.isEmpty()) continue

                                val timestamp = msgObj.get("timestamp")?.asLong ?: System.currentTimeMillis()
                                rawMessages.add(ChatMessage(
                                    id = "",  // placeholder, assigned below
                                    role = role,
                                    content = content,
                                    timestamp = timestamp,
                                    attachments = attachments
                                ))
                            } catch (e: Exception) {
                                Log.w(TAG, "Skipping unparseable history message", e)
                            }
                        }
                    }

                    // The tail of rawMessages corresponds to our existing messages.
                    // Reuse their IDs; only assign new IDs for the older prefix.
                    val newOlderCount = (rawMessages.size - oldCount).coerceAtLeast(0)
                    val olderMessages = rawMessages.take(newOlderCount).map {
                        it.copy(id = UUID.randomUUID().toString())
                    }

                    // Combined: new older messages + existing (with stable IDs)
                    val combined = olderMessages + existingMessages
                    _chatMessages.value = combined

                    // Did we get everything the gateway has?
                    // Use the raw count (including system messages) vs the limit we sent,
                    // NOT the filtered user/assistant count which is always smaller.
                    val hasMore = totalReturnedByGateway >= currentHistoryLimit

                    Log.d(TAG, "Prepended $newOlderCount older messages (total=${combined.size}, hasMore=$hasMore)")
                    _isLoadingMoreHistory.value = false
                    onMoreHistoryLoaded?.invoke(newOlderCount, hasMore)
                } else {
                    Log.e(TAG, "More history request failed: ${response.error}")
                    _isLoadingMoreHistory.value = false
                    onMoreHistoryLoaded?.invoke(0, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading more history for $key", e)
                _isLoadingMoreHistory.value = false
                onMoreHistoryLoaded?.invoke(0, false)
            }
        }
    }

    /**
     * Send a slash command (e.g., "/model", "/clear").
     */
    fun sendSlashCommand(command: String) {
        // Slash commands are just user messages starting with /
        sendMessage(command)
    }

    fun cleanup() {
        shouldReconnect = false
        scope.cancel()
        disconnect()
    }

    // ============== Internal methods ==============

    private suspend fun sendRequest(
        method: String,
        params: JsonObject? = null
    ): OpenClawResponse {
        val id = "${method}-${requestSeq.getAndIncrement()}"
        val request = OpenClawRequest(id = id, method = method, params = params)
        val deferred = CompletableDeferred<OpenClawResponse>()
        pendingRequests[id] = deferred

        val json = request.toJson()
        Log.d(TAG, "Sending request: method=$method id=$id bytes=${json.length}")
        if (webSocket?.send(json) != true) {
            pendingRequests.remove(id, deferred)
            throw IllegalStateException("Not connected")
        }

        return try {
            withTimeout(30_000) {
                deferred.await()
            }
        } finally {
            pendingRequests.remove(id, deferred)
        }
    }

    private fun handleFrame(json: String) {
        try {
            val obj = JsonParser.parseString(json).asJsonObject
            when (obj.get("type")?.asString) {
                "res" -> handleResponse(obj)
                "event" -> handleEvent(obj)
                else -> Log.w(TAG, "Unknown frame type (${json.length} bytes)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing frame (${json.length} bytes)", e)
        }
    }

    private fun handleResponse(obj: JsonObject) {
        val id = obj.get("id")?.asString ?: return
        val ok = obj.get("ok")?.asBoolean ?: false
        val payload = obj.getAsJsonObject("payload")
        val error = obj.getAsJsonObject("error")

        val response = OpenClawResponse(id = id, ok = ok, payload = payload, error = error)

        // Complete the pending request
        val deferred = pendingRequests.remove(id)
        if (deferred != null) {
            deferred.complete(response)
        } else {
            Log.d(TAG, "No pending request for id=$id (may be agent completion)")
        }
    }

    private fun handleEvent(obj: JsonObject) {
        val eventName = obj.get("event")?.asString ?: return
        val payload = obj.getAsJsonObject("payload")
        val event = OpenClawEvent(event = eventName, payload = payload)

        Log.d(TAG, "Received event: $eventName")

        when (eventName) {
            OpenClawEvents.CONNECT_CHALLENGE -> {
                challengeNonce = payload?.get("nonce")?.asString
                Log.d(TAG, "Received connect challenge")
                performAuth()
            }
            OpenClawEvents.CHAT -> {
                handleChatEvent(payload)
            }
            OpenClawEvents.AGENT -> {
                handleAgentEvent(payload)
            }
            "tick", OpenClawEvents.HEARTBEAT -> {
                // Keep-alive, no action needed
            }
            else -> {
                Log.d(TAG, "Unhandled event: $eventName")
            }
        }

        // Emit to shared flow for external observers
        scope.launch { _events.emit(event) }
    }

    /** Forward only the reasoning phase, never private reasoning content. */
    private fun handleAgentEvent(payload: JsonObject?) {
        payload ?: return
        val runId = payload.get("runId")?.takeIf { it.isJsonPrimitive }?.asString
        if (runId != null && (completedAbortedRuns.containsKey(runId) || runId != activeRunId)) return

        when (payload.get("stream")?.takeIf { it.isJsonPrimitive }?.asString) {
            "thinking", "reasoning" -> notifyAgentPhase("reasoning")
        }
    }

    private fun notifyAgentPhase(phase: String, onlyIfUnset: Boolean = false) {
        val messageId = activeMessageId ?: return
        if ((onlyIfUnset && lastAgentPhase != null) || lastAgentPhase == phase) return
        lastAgentPhase = phase
        when (phase) {
            "reasoning" -> updateRunState(RunState.REASONING)
            "aborting" -> updateRunState(RunState.ABORTING)
            else -> if (_runState.value != RunState.ABORTING) updateRunState(RunState.WAITING)
        }
        onAgentThinking?.invoke(AgentThinking(id = messageId, phase = phase))
    }

    private fun performAuth() {
        val nonce = challengeNonce
        if (nonce == null) {
            Log.e(TAG, "No challenge nonce available for auth")
            _connectionState.value = ConnectionState.Error("No challenge nonce")
            return
        }

        scope.launch {
            try {
                val params = JsonObject().apply {
                    addProperty("minProtocol", PROTOCOL_VERSION)
                    addProperty("maxProtocol", PROTOCOL_VERSION)

                    add("client", JsonObject().apply {
                        addProperty("id", "openclaw-control-ui")
                        addProperty("version", "1.0.0")
                        addProperty("platform", "android")
                        addProperty("mode", "ui")
                    })

                    addProperty("role", "operator")
                    add("scopes", JsonArray().apply {
                        add("operator.read")
                        add("operator.write")
                    })

                    add("auth", JsonObject().apply {
                        addProperty("token", token)
                    })

                    // Device identity for pairing
                    val signedAtMs = System.currentTimeMillis()
                    val scopesList = listOf("operator.read", "operator.write")
                    add("device", JsonObject().apply {
                        addProperty("id", deviceIdentity.deviceId)
                        addProperty("publicKey", deviceIdentity.publicKeyBase64Url)
                        addProperty("signature", deviceIdentity.signAuthPayload(
                            clientId = "openclaw-control-ui",
                            clientMode = "ui",
                            role = "operator",
                            scopes = scopesList,
                            signedAtMs = signedAtMs,
                            token = token,
                            nonce = nonce
                        ))
                        addProperty("signedAt", signedAtMs)
                        addProperty("nonce", nonce)
                        val savedToken = deviceIdentity.deviceToken
                        if (savedToken != null) {
                            addProperty("deviceToken", savedToken)
                        }
                    })

                    addProperty("locale", "nl-NL")
                    addProperty("userAgent", "clawsses-android/1.0.0")
                }

                Log.d(TAG, "Sending connect...")
                val response = sendRequest(OpenClawMethods.CONNECT, params)
                if (response.ok) {
                    Log.i(TAG, "Authentication successful!")

                    // Persist deviceToken if returned (from pairing approval)
                    val dt = response.payload?.get("deviceToken")?.asString
                    if (dt != null) {
                        deviceIdentity.deviceToken = dt
                        Log.d(TAG, "Persisted deviceToken")
                    }

                    // Extract the default session key from the hello-ok snapshot
                    val snapshot = response.payload?.getAsJsonObject("snapshot")
                    val sessionDefaults = snapshot?.getAsJsonObject("sessionDefaults")
                    val mainSessionKey = sessionDefaults?.get("mainSessionKey")?.asString
                    if (mainSessionKey != null) {
                        _currentSessionKey.value = mainSessionKey
                        Log.d(TAG, "Default session selected from gateway snapshot")
                    } else {
                        Log.w(TAG, "No default session in connect response")
                    }

                    _connectionState.value = ConnectionState.Connected
                    notifyConnectionUpdate(true, _currentSessionKey.value)

                    // Load history for the current session on connect
                    loadSessionHistory()
                } else {
                    val errorMsg = response.error?.get("message")?.asString ?: "Authentication failed"
                    val errorCode = response.error?.get("code")?.asString ?: ""
                    Log.e(TAG, "Authentication failed (code=$errorCode)")

                    if (errorCode == "pairing_required" || errorMsg.contains("pair", ignoreCase = true)) {
                        _connectionState.value = ConnectionState.PairingRequired(errorMsg)
                        // Keep reconnecting — user needs to approve on gateway
                    } else {
                        _connectionState.value = ConnectionState.Error(errorMsg)
                        shouldReconnect = false
                    }
                    webSocket?.close(1000, "Auth failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auth error", e)
                _connectionState.value = ConnectionState.Error("Auth error: ${e.message}")
            }
        }
    }

    /**
     * Handle a "chat" event from the gateway.
     * Payload structure:
     *   { runId, sessionKey, seq, state: "delta"|"final"|"aborted"|"error",
     *     message: { role, content: [{type:"text", text:"..."}] } }
     */
    private fun handleChatEvent(payload: JsonObject?) {
        payload ?: return
        val state = payload.get("state")?.asString ?: return
        val runId = payload.get("runId")?.asString
        val eventSessionKey = payload.get("sessionKey")?.asString

        if (runId != null && completedAbortedRuns.containsKey(runId)) return

        // Check if this event belongs to a different session than the currently active one.
        // If so, mark that session as having unread messages and don't render into the current view.
        val currentKey = _currentSessionKey.value
        if (eventSessionKey != null && currentKey != null && eventSessionKey != currentKey) {
            Log.d(TAG, "Chat event for inactive session (state=$state); marking unread")
            _unreadSessions.value = _unreadSessions.value + eventSessionKey
            // Still need to clean up our streaming state if this was our active run
            // (user switched sessions mid-stream)
            if (runId != null && runId == activeRunId) {
                if (state == "final" || state == "aborted" || state == "error") {
                    Log.d(TAG, "Clearing stale active run for inactive session")
                    activeRunId = null
                    activeMessageId = null
                    activeSessionKey = null
                    abortingRunId = null
                    streamingContent.clear()
                    updateRunState(if (state == "error") RunState.ERROR else RunState.IDLE)
                }
            }
            return
        }

        // Only process events for our active run
        if (runId != null && activeRunId != null && runId != activeRunId) return

        val msgId = activeMessageId ?: return

        when (state) {
            "delta" -> {
                if (runId == abortingRunId) return
                // Each delta contains the full accumulated text, not just the new chunk.
                // Diff against what we already have to extract only the new portion.
                val fullText = extractTextFromMessage(payload)
                val previous = streamingContent.toString()
                if (fullText.length > previous.length) {
                    val newChunk = fullText.substring(previous.length)
                    streamingContent.clear()
                    streamingContent.append(fullText)
                    onChatStream?.invoke(ChatStream(id = msgId, chunk = newChunk))
                    // Update phone UI with streaming text
                    updateStreamingMessage(msgId, fullText)
                    updateRunState(RunState.STREAMING)
                }
            }
            "final" -> {
                if (runId == abortingRunId) {
                    rememberAbortedRun(runId)
                    finalizeStreaming("aborted")
                    return
                }
                val fullText = extractTextFromMessage(payload)
                val previous = streamingContent.toString()
                if (fullText.isNotEmpty() && fullText.length > previous.length) {
                    val newChunk = fullText.substring(previous.length)
                    onChatStream?.invoke(ChatStream(id = msgId, chunk = newChunk))
                }
                // Use the final full text if available, otherwise keep what we accumulated
                if (fullText.isNotEmpty()) {
                    streamingContent.clear()
                    streamingContent.append(fullText)
                }
                finalizeStreaming("final")
            }
            "aborted", "error" -> {
                if (state == "aborted" && runId != null) rememberAbortedRun(runId)
                val errorMsg = payload.get("errorMessage")
                    ?.takeIf { it.isJsonPrimitive }?.asString
                Log.w(TAG, "Chat run ended with state=$state")
                finalizeStreaming(state, errorMsg)
            }
        }
    }

    /**
     * Extract text from a chat event message payload.
     * message.content is an array of {type:"text", text:"..."} blocks.
     */
    private fun extractTextFromMessage(payload: JsonObject): String {
        val message = payload.getAsJsonObject("message") ?: return ""
        val contentArray = message.getAsJsonArray("content") ?: return ""
        val sb = StringBuilder()
        for (element in contentArray) {
            val block = element.asJsonObject
            if (block.get("type")?.asString == "text") {
                val text = block.get("text")?.asString
                if (text != null) sb.append(text)
            }
        }
        return sb.toString()
    }

    private fun finalizeStreaming(terminalState: String, errorMessage: String? = null) {
        val msgId = activeMessageId ?: return
        val content = streamingContent.toString()

        if (content.isNotEmpty()) {
            val assistantMsg = ChatMessage(
                id = msgId,
                role = "assistant",
                content = content
            )
            // Update in place if already in the list (from streaming), otherwise add
            updateOrAddChatMessage(assistantMsg)
            // Send the complete finalized message to glasses so they have the full
            // content even if any streaming chunks were missed
            onChatMessage?.invoke(assistantMsg)
        }

        onChatStreamEnd?.invoke(ChatStreamEnd(id = msgId, state = terminalState))

        activeRunId = null
        activeMessageId = null
        activeSessionKey = null
        abortingRunId = null
        streamingContent.clear()
        lastAgentPhase = null
        updateRunState(
            if (terminalState == "error") RunState.ERROR else RunState.IDLE,
            errorMessage
        )
    }

    private fun finishRunWithoutContent(terminalState: String, errorMessage: String? = null) {
        activeMessageId?.let { onChatStreamEnd?.invoke(ChatStreamEnd(id = it, state = terminalState)) }
        activeRunId = null
        activeMessageId = null
        activeSessionKey = null
        abortingRunId = null
        streamingContent.clear()
        lastAgentPhase = null
        updateRunState(
            if (terminalState == "error") RunState.ERROR else RunState.IDLE,
            errorMessage
        )
    }

    private fun updateRunState(state: RunState, error: String? = null) {
        _runError.value = error
        _runState.value = state
    }

    private fun rememberAbortedRun(runId: String?) {
        if (runId == null) return
        completedAbortedRuns[runId] = System.currentTimeMillis()
        if (completedAbortedRuns.size > 64) {
            completedAbortedRuns.entries
                .sortedBy { it.value }
                .take(completedAbortedRuns.size - 64)
                .forEach { completedAbortedRuns.remove(it.key, it.value) }
        }
    }

    private fun addChatMessage(message: ChatMessage) {
        val current = _chatMessages.value.toMutableList()
        current.add(message)
        _chatMessages.value = current
    }

    /** Update existing message by id or add if not found */
    private fun updateOrAddChatMessage(message: ChatMessage) {
        val current = _chatMessages.value.toMutableList()
        val index = current.indexOfFirst { it.id == message.id }
        if (index >= 0) {
            current[index] = message
        } else {
            current.add(message)
        }
        _chatMessages.value = current
    }

    /** Update or insert a streaming assistant message in the chat list */
    private fun updateStreamingMessage(msgId: String, fullText: String) {
        val current = _chatMessages.value.toMutableList()
        val index = current.indexOfFirst { it.id == msgId }
        val msg = ChatMessage(id = msgId, role = "assistant", content = fullText)
        if (index >= 0) {
            current[index] = msg
        } else {
            current.add(msg)
        }
        _chatMessages.value = current
    }

    private fun notifyConnectionUpdate(connected: Boolean, sessionId: String? = null) {
        val sessionName = sessionId?.let { id ->
            val agentId = agentIdFromSessionKey(id)
            _agentList.value.firstOrNull { it.id == agentId }?.name
                ?: _sessionList.value.firstOrNull { it.key == id }?.name
        }
        onConnectionUpdate?.invoke(ConnectionUpdate(
            connected = connected,
            sessionId = sessionId,
            sessionName = sessionName
        ))
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            val state = _connectionState.value
            if (state is ConnectionState.Disconnected || state is ConnectionState.Error || state is ConnectionState.PairingRequired) {
                connect(host, port, token)
            }
        }
    }

    private fun failAllPending(reason: String) {
        pendingRequests.forEach { (id, deferred) ->
            deferred.completeExceptionally(Exception(reason))
        }
        pendingRequests.clear()
    }

    /** Parse text and embedded image blocks without fetching remote image URLs. */
    private fun parseContentArray(contentArray: JsonArray): Pair<String, List<ChatAttachment>> {
        val text = StringBuilder()
        val attachments = mutableListOf<ChatAttachment>()

        for (element in contentArray) {
            if (!element.isJsonObject) continue
            val block = element.asJsonObject
            when (block.get("type")?.asString) {
                "text", "input_text", "output_text" -> {
                    block.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.let(text::append)
                }
                "image", "input_image" -> {
                    val candidates = listOfNotNull(
                        block.get("base64")?.takeIf { it.isJsonPrimitive }?.asString,
                        block.get("content")?.takeIf { it.isJsonPrimitive }?.asString,
                        block.get("url")?.takeIf { it.isJsonPrimitive }?.asString,
                        nestedPrimitiveString(block, "data", "url"),
                        nestedPrimitiveString(block, "image_url", "url")
                    )
                    val embedded = candidates.firstOrNull { value ->
                        value.startsWith("data:image/") || !value.contains("://")
                    } ?: continue
                    val commaIndex = embedded.indexOf(',')
                    val base64 = if (embedded.startsWith("data:") && commaIndex >= 0) {
                        embedded.substring(commaIndex + 1)
                    } else {
                        embedded
                    }
                    if (base64.length > 12_000_000) continue
                    val dataMime = embedded
                        .takeIf { it.startsWith("data:") }
                        ?.substringAfter("data:")
                        ?.substringBefore(';')
                    attachments += ChatAttachment(
                        mimeType = block.get("mimeType")?.takeIf { it.isJsonPrimitive }?.asString
                            ?: dataMime,
                        fileName = block.get("fileName")?.takeIf { it.isJsonPrimitive }?.asString,
                        base64 = base64
                    )
                }
            }
        }
        return text.toString() to attachments
    }

    private fun nestedPrimitiveString(parent: JsonObject, objectName: String, valueName: String): String? {
        val nested = parent.get(objectName)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return nested.get(valueName)?.takeIf { it.isJsonPrimitive }?.asString
    }

    /** Detect image MIME type from base64 magic bytes. */
    private fun detectImageMimeType(base64: String): String {
        // Decode just enough bytes to check the magic header
        val prefix = base64.take(16)
        return try {
            val bytes = android.util.Base64.decode(prefix, android.util.Base64.DEFAULT)
            when {
                bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/webp"
                bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
                else -> "image/webp" // Default for CXR SDK photos
            }
        } catch (e: Exception) {
            "image/webp"
        }
    }
}
