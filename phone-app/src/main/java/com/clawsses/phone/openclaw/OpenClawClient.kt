package com.clawsses.phone.openclaw

import android.util.Log
import com.clawsses.phone.BuildConfig
import com.clawsses.phone.media.ChatAttachmentFileStore
import com.clawsses.shared.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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

/**
 * WebSocket client for connecting to an OpenClaw Gateway.
 * Handles the connect handshake (with Ed25519 device identity),
 * request/response correlation, event streaming, and auto-reconnect.
 */
class OpenClawClient(
    private val deviceIdentity: DeviceIdentity,
    private val networkMonitor: NetworkMonitor? = null,
    private val attachmentFileStore: ChatAttachmentFileStore,
) {

    companion object {
        private const val TAG = "OpenClawClient"
        private const val RECONNECT_BASE_DELAY_MS = 3_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private const val PROTOCOL_VERSION = 4
        private const val STREAM_PUBLICATION_INTERVAL_MS = 64L
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

    private val chatRun = OpenClawChatRunComponent(attachmentFileStore)
    private val sessionSync = OpenClawSessionSyncCoordinator()
    private val chatStore get() = chatRun.chatStore
    val chatMessages: StateFlow<List<ChatMessage>> = chatStore.messages

    private val _events = MutableSharedFlow<OpenClawEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<OpenClawEvent> = _events.asSharedFlow()

    private val _runState get() = chatRun.runState
    val runState: StateFlow<RunState> = _runState.asStateFlow()

    private val _runError get() = chatRun.runError
    val runError: StateFlow<String?> = _runError.asStateFlow()

    // Callbacks for forwarding to glasses
    var onChatMessage: ((ChatMessage) -> Unit)? = null
    var onChatHistory: ((List<ChatMessage>) -> Unit)? = null
    var onAgentThinking: ((AgentThinking) -> Unit)? = null
    var onAgentProgress: ((AgentProgressUpdate) -> Unit)? = null
    var onChatStream: ((ChatStream) -> Unit)? = null
    var onChatStreamEnd: ((ChatStreamEnd) -> Unit)? = null
    var onSessionList: ((SessionListUpdate) -> Unit)? = null
    var onSessionOperation: ((SessionOperationUpdate) -> Unit)? = null
    var onAgentList: ((AgentListUpdate) -> Unit)? = null
    var onConnectionUpdate: ((ConnectionUpdate) -> Unit)? = null
    /** Fired after loadMoreHistory completes. Args: (prependedCount, hasMore) */
    var onMoreHistoryLoaded: ((Int, Boolean) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val transport = OpenClawTransportAuthComponent(
        networkInitiallyAvailable = networkMonitor?.isNetworkAvailable() ?: true,
        reconnectBaseDelayMs = RECONNECT_BASE_DELAY_MS,
        reconnectMaxDelayMs = RECONNECT_MAX_DELAY_MS,
    )
    private var webSocket by transport::webSocket
    private val client get() = transport.client
    private val requestCoordinator get() = transport.requestCoordinator
    private val connectionLock get() = transport.connectionLock
    private val connectionEpoch get() = transport.connectionEpoch
    private val reconnectBackoff get() = transport.reconnectBackoff

    // Connection params (saved for reconnect)
    private var host by transport::host
    private var port by transport::port
    private var token by transport::token
    private var shouldReconnect by transport::shouldReconnect
    private var reconnectJob by transport::reconnectJob
    private val networkAvailability get() = transport.networkAvailability

    // Active agent run tracking
    private var activeRunId by chatRun::activeRunId
    private var activeMessageId by chatRun::activeMessageId
    private var activeSessionKey by chatRun::activeSessionKey
    private var abortingRunId by chatRun::abortingRunId
    private val completedAbortedRuns get() = chatRun.completedAbortedRuns
    // Gateway deltas already carry the full immutable text. Retain that String directly instead
    // of copying the whole response into a StringBuilder for every delta.
    private var streamingContent by chatRun::streamingContent
    private var lastAgentPhase by chatRun::lastAgentPhase
    private var agentProgressActive by chatRun::agentProgressActive

    // Current session tracking (exposed as StateFlow for phone UI)
    private val catalogSession = OpenClawCatalogSessionComponent()
    private val activeSessionRuntime = OpenClawActiveSessionRuntime(
        catalogSession = catalogSession,
        sessionSync = sessionSync,
        chatStore = chatStore,
        attachmentFileStore = attachmentFileStore,
        sendRequest = { method, params, generation -> sendRequest(method, params, generation) },
        onChatHistory = { history -> onChatHistory?.invoke(history) },
        onMoreHistoryLoaded = { count, hasMore ->
            onMoreHistoryLoaded?.invoke(count, hasMore)
        },
    )
    private val _currentSessionKey get() = catalogSession.currentSessionKey
    val currentSessionKey: StateFlow<String?> = _currentSessionKey.asStateFlow()
    private var homeSessionKey by catalogSession::homeSessionKey

    // History pagination: tracks how many messages were last requested from OpenClaw
    private val _isLoadingMoreHistory get() = catalogSession.isLoadingMoreHistory
    val isLoadingMoreHistory: StateFlow<Boolean> = _isLoadingMoreHistory.asStateFlow()
    private val _hasMoreHistory get() = catalogSession.hasMoreHistory
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()

    // Sessions with unread messages (received while that session was not active)
    private val _unreadSessions get() = catalogSession.unreadSessions
    val unreadSessions: StateFlow<Set<String>> = _unreadSessions.asStateFlow()

    // Available sessions (exposed as StateFlow for phone UI)
    private val _sessionList get() = catalogSession.sessionList
    val sessionList: StateFlow<List<SessionInfo>> = _sessionList.asStateFlow()

    private val _agentList get() = catalogSession.agentList
    val agentList: StateFlow<List<AgentInfo>> = _agentList.asStateFlow()

    private val _modelList get() = catalogSession.modelList
    val modelList: StateFlow<List<ModelInfo>> = _modelList.asStateFlow()

    private val _currentModelRef get() = catalogSession.currentModelRef
    val currentModelRef: StateFlow<String?> = _currentModelRef.asStateFlow()

    private val _isSelectingModel get() = catalogSession.isSelectingModel
    val isSelectingModel: StateFlow<Boolean> = _isSelectingModel.asStateFlow()

    private val _modelSelectionError get() = catalogSession.modelSelectionError
    val modelSelectionError: StateFlow<String?> = _modelSelectionError.asStateFlow()

    // Challenge nonce for auth handshake
    private var challengeNonce by transport::challengeNonce

    private val streamPublisher = OpenClawStreamPublisher(
        publicationIntervalMs = STREAM_PUBLICATION_INTERVAL_MS,
        schedule = { delayMs, publication ->
            scope.launch {
                delay(delayMs)
                publication()
            }
        },
        publish = { update ->
            onChatStream?.invoke(ChatStream(id = update.messageId, chunk = update.chunk))
            updateStreamingMessage(update.messageId, update.fullText)
        },
        buffer = chatRun.streamUpdateBuffer,
    )

    init {
        networkMonitor?.start { available -> handleNetworkAvailability(available) }
    }

    fun connect(host: String, port: Int, token: String) {
        val normalizedHost = host.trim().trimEnd('/')
        if (normalizedHost.startsWith("ws://") || normalizedHost.startsWith("http://")) {
            val socket = synchronized(connectionLock) {
                shouldReconnect = false
                connectionEpoch.invalidate()
                reconnectJob?.cancel()
                reconnectJob = null
                webSocket.also { webSocket = null }
            }
            socket?.close(1000, "Insecure endpoint rejected")
            failAllPending("Insecure endpoint rejected")
            _connectionState.value = ConnectionState.Error("Secure WSS connection required")
            notifyConnectionUpdate(false)
            return
        }

        startConnection(normalizedHost, port, token, resetBackoff = true)
    }

    private fun startConnection(
        normalizedHost: String,
        port: Int,
        token: String,
        resetBackoff: Boolean,
    ) {
        if (!networkAvailability.isAvailable()) {
            synchronized(connectionLock) {
                this.host = normalizedHost
                this.port = port
                this.token = token
                shouldReconnect = true
                reconnectJob?.cancel()
                reconnectJob = null
                if (resetBackoff) reconnectBackoff.reset()
            }
            _connectionState.value = ConnectionState.Disconnected
            notifyConnectionUpdate(false)
            return
        }
        val previousSocket: WebSocket?
        val generation: Long
        synchronized(connectionLock) {
            if (resetBackoff) reconnectBackoff.reset()
            reconnectJob?.cancel()
            reconnectJob = null
            previousSocket = webSocket
            webSocket = null
            this.host = normalizedHost
            this.port = port
            this.token = token
            shouldReconnect = true
            generation = connectionEpoch.begin()
            challengeNonce = null
        }
        sessionSync.resetConnection()
        if (previousSocket != null) {
            previousSocket.close(1000, "Connection replaced")
            failAllPending("Connection replaced")
        }

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

        val newSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrentConnection(generation)) return
                Log.i(TAG, "WebSocket connected (HTTP ${response.code})")
                _connectionState.value = ConnectionState.Authenticating
                // Wait for connect.challenge event from server
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isCurrentConnection(generation)) handleFrame(text, generation)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (isCurrentConnection(generation)) handleFrame(bytes.utf8(), generation)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrentConnection(generation)) return
                Log.d(TAG, "WebSocket closing (code=$code)")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleConnectionEnded(
                    generation = generation,
                    socket = webSocket,
                    state = ConnectionState.Disconnected,
                    reason = "WebSocket closed (code=$code)",
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleConnectionEnded(
                    generation = generation,
                    socket = webSocket,
                    state = ConnectionState.Error("${t.javaClass.simpleName}: ${t.message}"),
                    reason = "WebSocket failed: ${t.javaClass.simpleName}",
                )
            }
        })
        synchronized(connectionLock) {
            if (connectionEpoch.isCurrent(generation) &&
                !connectionEpoch.isEnded(generation) &&
                shouldReconnect
            ) {
                webSocket = newSocket
            } else {
                newSocket.cancel()
            }
        }
    }

    fun disconnect() {
        val socket = synchronized(connectionLock) {
            shouldReconnect = false
            connectionEpoch.invalidate()
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectBackoff.reset()
            challengeNonce = null
            webSocket.also { webSocket = null }
        }
        sessionSync.resetConnection()
        socket?.close(1000, "User disconnected")
        _connectionState.value = ConnectionState.Disconnected
        notifyConnectionUpdate(false)
        failAllPending("Disconnected")
    }

    /**
     * Send a user message to OpenClaw and trigger an agent run.
     */
    fun sendMessage(
        text: String,
        images: List<String>? = null,
        clientMessageId: String? = null,
    ) {
        val previousState = _runState.value
        if (previousState !in setOf(RunState.IDLE, RunState.ERROR) ||
            !_runState.compareAndSet(previousState, RunState.WAITING)) {
            Log.w(TAG, "Ignoring send while another agent run is active")
            return
        }
        _runError.value = null
        clearAgentProgress(force = true)
        scope.launch {
            try {
                val idempotencyKey = UUID.randomUUID().toString()
                val sessionKey = _currentSessionKey.value ?: "main"

                // Add user message to local chat and remember its canonical echo key.
                val userMsgId = clientMessageId?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
                val localAttachments = images.orEmpty().mapNotNull { base64 ->
                    ChatAttachment(
                        mimeType = detectImageMimeType(base64),
                        base64 = base64
                    ).let(attachmentFileStore::materialize)
                }
                val userMsg = ChatMessage(
                    id = userMsgId,
                    role = "user",
                    content = text,
                    attachments = localAttachments
                )
                sessionSync.registerOptimistic(sessionKey, idempotencyKey, userMsgId)
                addChatMessage(userMsg)
                onChatMessage?.invoke(userMsg)

                // Send to OpenClaw as chat.send
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
                streamingContent = ""
                streamPublisher.reset()
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
    fun requestSessions() {
        scope.launch {
            try {
                val params = JsonObject().apply {
                    addProperty("includeDerivedTitles", true)
                }
                val response = sendRequest(OpenClawMethods.SESSION_LIST, params)
                if (response.ok) {
                    val sessions = parseSessions(response.payload)
                    _sessionList.value = sessions
                } else {
                    Log.e(TAG, "Session list request failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting sessions", e)
            }
        }
    }

    /** Request one compact page for the CXR size-constrained glasses picker. */
    fun requestSessionPage(offset: Int = 0) {
        val safeOffset = offset.coerceAtLeast(0)
        onSessionOperation?.invoke(
            SessionOperationUpdate(operation = "list", state = "loading")
        )
        scope.launch {
            try {
                val response = sendRequest(
                    OpenClawMethods.SESSION_LIST,
                    SessionRequestFactory.listPageParams(safeOffset)
                )
                if (!response.ok) {
                    onSessionOperation?.invoke(
                        SessionOperationUpdate(
                            operation = "list",
                            state = "error",
                            error = responseErrorMessage(response, "Could not load sessions")
                        )
                    )
                    return@launch
                }

                onSessionList?.invoke(catalogSession.sessionPage(response.payload, safeOffset))
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting session page", e)
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

    private fun parseSessions(payload: JsonObject?): List<SessionInfo> =
        catalogSession.parseSessions(payload)

    /** Request the read-only agent roster exposed by the gateway. */
    fun requestAgents() {
        scope.launch {
            try {
                val response = sendRequest(OpenClawMethods.AGENTS_LIST, JsonObject())
                if (!response.ok) {
                    Log.e(TAG, "Agent list request failed")
                    return@launch
                }

                val defaultAgentId = catalogSession.applyAgentCatalog(response.payload)
                onAgentList?.invoke(currentAgentListUpdate(defaultAgentId))
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting agents", e)
            }
        }
    }

    /** Load the same configured model catalog shown by OpenClaw WebChat. */
    fun requestModels() {
        scope.launch {
            try {
                val response = sendRequest(
                    OpenClawMethods.MODELS_LIST,
                    JsonObject().apply { addProperty("view", "configured") }
                )
                if (!response.ok) {
                    _modelSelectionError.value = responseErrorMessage(
                        response,
                        "Could not load models"
                    )
                    return@launch
                }
                val models = parseConfiguredModels(response.payload)
                val sessionKey = _currentSessionKey.value
                val sessionModel = sessionKey?.let { key ->
                    val sessionsResponse = sendRequest(
                        OpenClawMethods.SESSION_LIST,
                        JsonObject().apply {
                            addProperty("search", key)
                            addProperty("limit", 10)
                            addProperty("includeGlobal", true)
                        }
                    )
                    if (sessionsResponse.ok) {
                        resolveSessionModelRef(
                            payload = sessionsResponse.payload,
                            sessionKey = key,
                            models = models,
                        )
                    } else {
                        null
                    }
                }
                catalogSession.applyModelCatalog(models, sessionModel)
                onAgentList?.invoke(currentAgentListUpdate())
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting models", e)
                _modelSelectionError.value = "Could not load models"
            }
        }
    }

    /** Select one configured model through canonical write-scoped sessions.patch. */
    fun selectModel(model: ModelInfo) {
        if (!model.available || _isSelectingModel.value) return
        val sessionKey = _currentSessionKey.value
        if (sessionKey.isNullOrBlank()) {
            _modelSelectionError.value = "No active session"
            return
        }

        _isSelectingModel.value = true
        _modelSelectionError.value = null
        scope.launch {
            try {
                val response = sendRequest(
                    OpenClawMethods.SESSION_PATCH,
                    SessionRequestFactory.modelPatchParams(sessionKey, model.ref),
                )
                if (response.ok) {
                    _currentModelRef.value = model.ref
                    onAgentList?.invoke(currentAgentListUpdate())
                } else {
                    _modelSelectionError.value = responseErrorMessage(
                        response,
                        "Could not change model"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error selecting model", e)
                _modelSelectionError.value = "Could not change model"
            } finally {
                _isSelectingModel.value = false
            }
        }
    }

    fun currentAgentListUpdate(defaultAgentId: String? = null): AgentListUpdate {
        return catalogSession.currentAgentListUpdate(defaultAgentId)
    }

    /** Select an agent's canonical main session, which is created lazily if needed. */
    fun switchAgent(agentId: String, displayName: String? = null) {
        val normalizedId = agentId.trim()
        if (normalizedId.isEmpty() || ':' in normalizedId) return

        scope.launch {
            val key = "agent:$normalizedId:main"
            val operation = activateSession(key)
            onConnectionUpdate?.invoke(
                ConnectionUpdate(
                    connected = true,
                    sessionId = key,
                    sessionName = displayName ?: _agentList.value.firstOrNull { it.id == normalizedId }?.name
                        ?: normalizedId
                )
            )
            synchronizeActiveSession(operation)
            requestModels()
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
                    val operation = activateSession(newKey)
                    notifyConnectionUpdate(true, newKey)
                    synchronizeActiveSession(operation)
                    onSessionOperation?.invoke(
                        SessionOperationUpdate(operation = "create", state = "success")
                    )
                    requestModels()
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
            val operation = activateSession(sessionKey)
            notifyConnectionUpdate(true, sessionKey)
            synchronizeActiveSession(operation)
            requestModels()
        }
    }

    /**
     * Load chat history for the current (or given) session from the gateway.
     * Fetches messages via chat.history, populates local chat, and forwards to glasses.
     * Always notifies glasses (even for empty history) so they can clear stale messages.
     */
    fun loadSessionHistory(sessionKey: String? = null) {
        val operation = activeSessionRuntime.prepareHistoryLoad(sessionKey)
        scope.launch { activeSessionRuntime.loadHistory(operation) }
    }

    private suspend fun synchronizeActiveSession(
        operation: OpenClawActiveSessionRuntime.Operation,
        requiredGeneration: Long? = null,
    ) {
        activeSessionRuntime.synchronize(operation, requiredGeneration)
    }

    private fun refreshActiveHistory(reason: String) {
        scope.launch { activeSessionRuntime.refresh(reason) }
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
        val claim = activeSessionRuntime.claimMoreHistory() ?: return
        scope.launch { activeSessionRuntime.loadMoreHistory(claim) }
    }

    /**
     * Send a slash command (e.g., "/model", "/clear").
     */
    fun sendSlashCommand(command: String) {
        // Slash commands are just user messages starting with /
        sendMessage(command)
    }

    fun cleanup() {
        disconnect()
        networkMonitor?.stop()
        scope.cancel()
    }

    // ============== Internal methods ==============

    private suspend fun sendRequest(
        method: String,
        params: JsonObject? = null,
        requiredGeneration: Long? = null,
    ): OpenClawResponse {
        val pending = requestCoordinator.register(method)
        val request = OpenClawRequest(id = pending.id, method = method, params = params)

        val json = request.toJson()
        Log.d(TAG, "Sending request: method=$method id=${pending.id} bytes=${json.length}")
        val sent = synchronized(connectionLock) {
            if (requiredGeneration == null || connectionEpoch.isCurrent(requiredGeneration)) {
                webSocket?.send(json) == true
            } else {
                false
            }
        }
        if (!sent) {
            requestCoordinator.cancel(pending)
            throw IllegalStateException("Not connected")
        }

        return try {
            withTimeout(30_000) {
                pending.response.await()
            }
        } finally {
            requestCoordinator.cancel(pending)
        }
    }

    private fun handleFrame(json: String, generation: Long) {
        if (!isCurrentConnection(generation)) return
        when (val frame = OpenClawWireCodec.decode(json)) {
            is GatewayFrame.Response -> handleResponse(frame.value)
            is GatewayFrame.Event -> handleEvent(frame.value, generation)
            is GatewayFrame.Unknown -> Log.w(TAG, "Unknown frame type (${json.length} bytes)")
            is GatewayFrame.Malformed -> Log.e(
                TAG,
                "Error parsing frame (${json.length} bytes): ${frame.reason}",
            )
        }
    }

    private fun handleResponse(response: OpenClawResponse) {
        if (!requestCoordinator.resolve(response)) {
            Log.d(TAG, "No pending request for id=${response.id} (may be agent completion)")
        }
    }

    private fun handleEvent(event: OpenClawEvent, generation: Long) {
        if (!isCurrentConnection(generation)) return
        val eventName = event.event
        val payload = event.payload

        Log.d(TAG, "Received event: $eventName")

        when (eventName) {
            OpenClawEvents.CONNECT_CHALLENGE -> {
                challengeNonce = payload?.get("nonce")?.asString
                Log.d(TAG, "Received connect challenge")
                performAuth(generation)
            }
            OpenClawEvents.CHAT -> {
                handleChatEvent(payload)
            }
            OpenClawEvents.SESSION_MESSAGE -> {
                handleSessionMessageEvent(payload)
            }
            OpenClawEvents.SESSIONS_CHANGED -> {
                handleSessionsChangedEvent(payload)
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

    /** Forward phases and privacy-filtered progress, never private reasoning content. */
    private fun handleAgentEvent(payload: JsonObject?) {
        payload ?: return
        val runId = payload.get("runId")?.takeIf { it.isJsonPrimitive }?.asString
        if (runId != null && (completedAbortedRuns.containsKey(runId) || runId != activeRunId)) return

        val stream = payload.get("stream")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        when (stream) {
            "thinking", "reasoning" -> notifyAgentPhase("reasoning")
        }
        val data = payload.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        AgentProgressProjector.project(stream, data)?.let { update ->
            agentProgressActive = true
            onAgentProgress?.invoke(update)
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

    private fun clearAgentProgress(force: Boolean = false) {
        if (!force && !agentProgressActive) return
        agentProgressActive = false
        onAgentProgress?.invoke(AgentProgressUpdate.clear())
    }

    private fun performAuth(generation: Long) {
        if (!isCurrentConnection(generation)) return
        val nonce = challengeNonce
        if (nonce == null) {
            Log.e(TAG, "No challenge nonce available for auth")
            _connectionState.value = ConnectionState.Error("No challenge nonce")
            return
        }

        scope.launch {
            try {
                val authToken = synchronized(connectionLock) {
                    token.takeIf { connectionEpoch.isCurrent(generation) }
                } ?: return@launch
                val params = OpenClawAuthRequestFactory.create(
                    protocolVersion = PROTOCOL_VERSION,
                    appVersion = BuildConfig.VERSION_NAME,
                    token = authToken,
                    nonce = nonce,
                    deviceIdentity = deviceIdentity,
                    signedAtMs = System.currentTimeMillis(),
                )

                Log.d(TAG, "Sending connect...")
                val response = sendRequest(
                    OpenClawMethods.CONNECT,
                    params,
                    requiredGeneration = generation,
                )
                if (!isCurrentConnection(generation)) return@launch
                if (response.ok) {
                    Log.i(TAG, "Authentication successful!")
                    synchronized(connectionLock) { reconnectBackoff.reset() }

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
                    var sessionOperation: OpenClawActiveSessionRuntime.Operation? = null
                    if (mainSessionKey != null) {
                        homeSessionKey = mainSessionKey
                        sessionOperation = activateSession(mainSessionKey)
                        Log.d(TAG, "Default session selected from gateway snapshot")
                    } else {
                        Log.w(TAG, "No default session in connect response")
                        if (_currentSessionKey.value == null) {
                            sessionOperation = activateSession("main")
                        }
                    }

                    _connectionState.value = ConnectionState.Connected
                    notifyConnectionUpdate(true, _currentSessionKey.value)

                    try {
                        val broadSubscription = sendRequest(
                            OpenClawMethods.SESSIONS_SUBSCRIBE,
                            JsonObject(),
                            requiredGeneration = generation,
                        )
                        if (!broadSubscription.ok) {
                            Log.w(TAG, "Broad session subscription was rejected")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not subscribe to broad session changes", e)
                    }
                    val activeKey = _currentSessionKey.value
                    if (activeKey != null) {
                        synchronizeActiveSession(
                            operation = sessionOperation
                                ?: activeSessionRuntime.currentOperation(activeKey),
                            requiredGeneration = generation,
                        )
                    }
                } else {
                    val errorMsg = response.error?.get("message")?.asString ?: "Authentication failed"
                    val errorCode = response.error?.get("code")?.asString ?: ""
                    Log.e(TAG, "Authentication failed (code=$errorCode)")

                    if (errorCode == "pairing_required" || errorMsg.contains("pair", ignoreCase = true)) {
                        _connectionState.value = ConnectionState.PairingRequired(errorMsg)
                        // Keep reconnecting — user needs to approve on gateway
                    } else {
                        _connectionState.value = ConnectionState.Error(errorMsg)
                        synchronized(connectionLock) {
                            if (connectionEpoch.isCurrent(generation)) shouldReconnect = false
                        }
                    }
                    currentSocket(generation)?.close(1000, "Auth failed")
                }
            } catch (e: Exception) {
                if (!isCurrentConnection(generation)) return@launch
                Log.e(TAG, "Auth error", e)
                _connectionState.value = ConnectionState.Error("Auth error: ${e.message}")
                currentSocket(generation)?.close(1011, "Auth error")
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
        val event = ChatEventParser.parse(payload) ?: return
        when (val plan = chatRun.plan(event, _currentSessionKey.value)) {
            ChatEventPlan.Ignore -> Unit
            is ChatEventPlan.InactiveSession -> {
                Log.d(TAG, "Chat event for inactive session; marking unread")
                _unreadSessions.value = _unreadSessions.value + plan.sessionKey
                if (plan.terminalActiveRun) {
                    Log.d(TAG, "Clearing stale active run for inactive session")
                    chatRun.resetActiveRun()
                    updateRunState(
                        if (plan.terminalState == "error") RunState.ERROR else RunState.IDLE,
                    )
                }
            }
            is ChatEventPlan.Delta -> {
                streamingContent = plan.fullText
                clearAgentProgress()
                updateRunState(RunState.STREAMING)
                streamPublisher.enqueue(plan.messageId, plan.fullText, plan.newChunk)
            }
            is ChatEventPlan.Final -> {
                if (plan.newChunk != null) {
                    streamingContent = plan.fullText
                    streamPublisher.enqueue(plan.messageId, plan.fullText, plan.newChunk)
                }
                streamPublisher.flush()
                finalizeStreaming("final")
            }
            is ChatEventPlan.Terminal -> {
                rememberAbortedRun(plan.rememberAbortedRunId)
                Log.w(TAG, "Chat run ended with state=${plan.state}")
                finalizeStreaming(plan.state, plan.errorMessage)
            }
        }
        if (event.state in setOf("final", "aborted", "error") &&
            (event.sessionKey == null || event.sessionKey == _currentSessionKey.value)
        ) {
            refreshActiveHistory("terminal run")
        }
    }

    private fun handleSessionMessageEvent(payload: JsonObject?) {
        val event = SessionMessageEventParser.parse(payload) ?: return
        if (event.sessionKey != _currentSessionKey.value) {
            _unreadSessions.value = _unreadSessions.value + event.sessionKey
            scheduleCatalogRefresh()
            return
        }
        val decision = sessionSync.acceptMessage(event)
        if (decision !is OpenClawSessionSyncCoordinator.MessageDecision.Accept) return
        if (decision.sequenceGap) refreshActiveHistory("message sequence gap")

        // The local chat event stream owns its assistant tail until terminal reconciliation.
        if (event.message.role == "assistant" && activeRunId != null) return

        val canonical = attachmentFileStore.materialize(listOf(event.message)).single()
        val existed = chatStore.value().firstOrNull { it.id == canonical.id }
        val result = chatStore.reconcileCanonical(canonical, decision.replacingLocalId)
        if (!result.changed) return
        if (decision.replacingLocalId != null || existed != null) {
            onChatHistory?.invoke(result.messages)
        } else {
            onChatMessage?.invoke(canonical)
        }
    }

    private fun handleSessionsChangedEvent(payload: JsonObject?) {
        val event = SessionsChangedEventParser.parse(payload) ?: return
        val currentKey = _currentSessionKey.value
        if (event.sessionKey != currentKey) {
            if (event.phase == "message") {
                _unreadSessions.value = _unreadSessions.value + event.sessionKey
            }
        } else if (event.phase == "message") {
            refreshActiveHistory("session transcript invalidated")
        }
        scheduleCatalogRefresh(refreshModels = event.sessionKey == currentKey && event.reason != null)
    }

    private fun scheduleCatalogRefresh(refreshModels: Boolean = false) {
        if (!sessionSync.claimCatalogRefresh()) return
        scope.launch {
            try {
                delay(150)
                requestSessions()
                if (refreshModels) requestModels()
            } finally {
                sessionSync.completeCatalogRefresh()
            }
        }
    }

    private fun finalizeStreaming(terminalState: String, errorMessage: String? = null) {
        val msgId = activeMessageId ?: return
        val content = streamingContent
        clearAgentProgress()

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

        chatRun.resetActiveRun()
        updateRunState(
            if (terminalState == "error") RunState.ERROR else RunState.IDLE,
            errorMessage
        )
    }

    private fun finishRunWithoutContent(terminalState: String, errorMessage: String? = null) {
        clearAgentProgress()
        activeMessageId?.let { onChatStreamEnd?.invoke(ChatStreamEnd(id = it, state = terminalState)) }
        chatRun.resetActiveRun()
        updateRunState(
            if (terminalState == "error") RunState.ERROR else RunState.IDLE,
            errorMessage
        )
    }

    private fun updateRunState(state: RunState, error: String? = null) {
        _runError.value = error
        _runState.value = state
    }

    private fun activateSession(sessionKey: String): OpenClawActiveSessionRuntime.Operation =
        activeSessionRuntime.activate(sessionKey)

    private fun rememberAbortedRun(runId: String?) {
        chatRun.rememberAbortedRun(runId)
    }

    private fun addChatMessage(message: ChatMessage) {
        chatRun.add(message)
    }

    /** Update existing message by id or add if not found */
    private fun updateOrAddChatMessage(message: ChatMessage) {
        chatRun.upsertCompleted(message)
    }

    /** Update or insert a streaming assistant message in the chat list */
    private fun updateStreamingMessage(msgId: String, fullText: String) {
        chatRun.updateStreaming(msgId, fullText)
    }

    private fun notifyConnectionUpdate(connected: Boolean, sessionId: String? = null) {
        val sessionName = sessionId?.let { id ->
            if (id == homeSessionKey) {
                "Home"
            } else {
                val agentId = agentIdFromSessionKey(id)
                _sessionList.value.firstOrNull { it.key == id }?.name
                    ?: _agentList.value.firstOrNull { it.id == agentId }?.name
            }
        }
        onConnectionUpdate?.invoke(ConnectionUpdate(
            connected = connected,
            sessionId = sessionId,
            sessionName = sessionName
        ))
    }

    private fun isCurrentConnection(generation: Long): Boolean = synchronized(connectionLock) {
        connectionEpoch.isCurrent(generation)
    }

    private fun currentSocket(generation: Long): WebSocket? = synchronized(connectionLock) {
        webSocket.takeIf { connectionEpoch.isCurrent(generation) }
    }

    private fun handleConnectionEnded(
        generation: Long,
        socket: WebSocket,
        state: ConnectionState,
        reason: String,
    ) {
        val reconnect = synchronized(connectionLock) {
            if (!connectionEpoch.markEnded(generation)) return
            if (webSocket === socket) webSocket = null
            shouldReconnect
        }
        when (state) {
            is ConnectionState.Error -> Log.e(TAG, reason)
            else -> Log.d(TAG, reason)
        }
        _connectionState.value = state
        sessionSync.resetConnection()
        notifyConnectionUpdate(false)
        failAllPending("Connection lost")
        if (reconnect) scheduleReconnect(generation)
    }

    private fun scheduleReconnect(endedConnectionGeneration: Long) {
        synchronized(connectionLock) {
            if (!shouldReconnect ||
                !networkAvailability.isAvailable() ||
                !connectionEpoch.isCurrent(endedConnectionGeneration) ||
                reconnectJob?.isActive == true
            ) {
                return
            }
            val delayMs = reconnectBackoff.nextDelayMs()
            reconnectJob = scope.launch {
                delay(delayMs)
                val params = synchronized(connectionLock) {
                    if (!shouldReconnect || !connectionEpoch.isCurrent(endedConnectionGeneration)) {
                        reconnectJob = null
                        null
                    } else {
                        reconnectJob = null
                        Triple(host, port, token)
                    }
                } ?: return@launch
                startConnection(
                    normalizedHost = params.first,
                    port = params.second,
                    token = params.third,
                    resetBackoff = false,
                )
            }
        }
    }

    private fun handleNetworkAvailability(available: Boolean) {
        if (networkAvailability.update(available) == NetworkAvailabilityChange.UNCHANGED) return
        val socketToCancel: WebSocket?
        val reconnectParams: Triple<String, Int, String>?
        synchronized(connectionLock) {
            reconnectJob?.cancel()
            reconnectJob = null
            if (!available) {
                connectionEpoch.invalidate()
                socketToCancel = webSocket
                webSocket = null
                reconnectParams = null
            } else {
                socketToCancel = null
                reconnectBackoff.reset()
                reconnectParams = if (shouldReconnect && host.isNotBlank()) Triple(host, port, token) else null
            }
        }
        if (!available) {
            sessionSync.resetConnection()
            socketToCancel?.cancel()
            failAllPending("Network unavailable")
            _connectionState.value = ConnectionState.Disconnected
            notifyConnectionUpdate(false)
            return
        }
        reconnectParams?.let { params ->
            startConnection(
                normalizedHost = params.first,
                port = params.second,
                token = params.third,
                resetBackoff = false,
            )
        }
    }

    private fun failAllPending(reason: String) {
        requestCoordinator.failAll(reason)
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

internal fun buildAgentListUpdate(
    agents: List<AgentInfo>,
    currentAgentId: String?,
    currentModelRef: String?,
): AgentListUpdate = AgentListUpdate(
    agents = agents.map { agent ->
        if (agent.id == currentAgentId && !currentModelRef.isNullOrBlank()) {
            agent.copy(model = currentModelRef)
        } else {
            agent
        }
    },
    currentAgentId = currentAgentId,
)
