package com.clawsses.phone.glasses

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.clawsses.shared.CxrPayloadLimits
import com.clawsses.phone.BuildConfig
import com.clawsses.phone.util.SecurePreferences
import com.rokid.cxr.Caps
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.ApkStatusCallback
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.callbacks.GlassInfoResultCallback
import com.rokid.cxr.client.extend.callbacks.GlassVersionCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiHotStatusCallback
import com.rokid.cxr.client.extend.callbacks.PhotoResultCallback
import com.rokid.cxr.client.extend.infos.RKAppInfo
import com.rokid.cxr.client.extend.infos.GlassInfo
import com.rokid.cxr.client.extend.infos.SceneStatusInfo
import com.rokid.cxr.client.extend.listeners.BrightnessUpdateListener
import com.rokid.cxr.client.extend.listeners.CustomCmdListener
import com.rokid.cxr.client.extend.listeners.AudioStreamListener
import com.rokid.cxr.client.extend.listeners.SceneStatusUpdateListener
import com.rokid.cxr.client.utils.ValueUtil
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress

/**
 * Manages Rokid CXR-M SDK initialization and lifecycle.
 *
 * Connection flow:
 * 1. initialize(context) - Get CxrApi singleton, set up listeners
 * 2. initBluetooth(device) - Start Bluetooth init with discovered device
 *    -> callback.onConnectionInfo(socketUuid, macAddress, rokidAccount, glassesType)
 * 3. connectBluetooth(socketUuid, macAddress) - Complete connection
 *    -> callback.onConnected()
 * 4. (Optional) initWifiP2P() - For APK uploads
 *
 * SN verification: The SDK performs an AES-encrypted serial number check after
 * Bluetooth connects. On first attempt, SN_CHECK_FAILED is expected — we read
 * the glasses SN from the SDK via reflection, generate the correct encrypted
 * content, and reconnect automatically.
 */
object RokidSdkManager {

    private const val TAG = "RokidSdkManager"
    private const val GLASSES_APP_PACKAGE = "com.clawsses.glasses"
    private const val GLASSES_APP_ACTIVITY = "com.clawsses.glasses.HudActivity"
    private const val ROKID_LAUNCHER_PACKAGE = "com.rokid.os.sprite.launcher"
    private const val HUD_RECOVERY_DELAY_MS = 250L
    private const val HUD_RECOVERY_FALLBACK_DELAY_MS = 1_000L
    private const val CLASSIC_BLUETOOTH_ACTIVATION_DELAY_MS = 350L
    private const val APK_UPLOAD_PORT = 8848
    private const val HOTSPOT_PROBE_ATTEMPTS = 6
    private const val HOTSPOT_PROBE_CONNECT_TIMEOUT_MS = 500
    private const val HOTSPOT_PROBE_RETRY_DELAY_MS = 250L
    private const val DIRECT_AUDIO_CODEC_PCM = 1
    // CXR-M 1.2.x added an audio record "mode" parameter. This is not a
    // sample rate: both 24_000 and legacy/default mode 0 are accepted at the
    // request layer but never start a stream on Sprite firmware 1.24. Mode 1
    // is therefore evaluated as the remaining hardware A/B candidate.
    private const val DIRECT_AUDIO_RECORD_MODE = 1
    private const val DIRECT_AUDIO_STREAM_TYPE = "AI_assistant"

    private var isInitialized = false
    private var cxrApi: CxrApi? = null
    private var appContext: Context? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val hudForegroundRecovery = HudForegroundRecovery(
        launcherPackage = ROKID_LAUNCHER_PACKAGE,
        hudPackage = GLASSES_APP_PACKAGE,
    )
    private val reopenHudRunnable = Runnable {
        if (hudForegroundRecovery.consumeScheduledRecovery(
                connected = isConnected(),
                nowMs = SystemClock.elapsedRealtime(),
            )
        ) {
            Log.i(TAG, "Recovering Clawsses HUD after its AI scene exited")
            openGlassesApp()
        } else {
            Log.d(TAG, "Skipping HUD recovery because authorization expired or was cancelled")
        }
    }

    // Connection state
    private var isBluetoothConnectedState = false
    private var isWifiP2PConnectedState = false
    private var hotspotNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private val hotspotAttemptGate = HotspotAttemptGate()
    private val hotspotAttemptLock = Any()
    private val hotspotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeHotspotAttempt: ActiveHotspotAttempt? = null
    private val _capabilities = MutableStateFlow(GlassesCapabilitySnapshot())
    val capabilities: StateFlow<GlassesCapabilitySnapshot> = _capabilities.asStateFlow()

    data class GlassesHotspotConnection(
        val attemptId: Long,
        val ipAddress: String,
    )

    private data class ActiveHotspotAttempt(
        val id: Long,
        val completion: CompletableDeferred<GlassesHotspotConnection>,
        var probeJob: Job? = null,
        var probeStarted: Boolean = false,
    )

    // Saved connection info for reconnection
    private var savedSocketUuid: String? = null
    private var savedMacAddress: String? = null
    private var savedRokidAccount: String? = null
    private var savedDeviceName: String? = null
    private val connectionAttemptGate = CxrConnectionAttemptGate()

    // The init callback may advance only the attempt that created it.
    private var pendingConnectAttemptId: Long? = null

    // Last known brightness from glasses (tracked via BrightnessUpdateListener)
    private var lastKnownBrightness: Int = 15
    private var aiSceneRunning = false
    private val aiActivationGate = AiActivationGate()
    private var classicBluetoothActivationRequested = false
    private val activateClassicBluetoothRunnable = Runnable {
        if (!isConnected() || classicBluetoothActivationRequested) return@Runnable
        classicBluetoothActivationRequested = true
        runCatching {
            cxrApi?.activeBluetoothConnect()
            Log.i(TAG, "Requested glasses-initiated classic Bluetooth connection")
        }.onFailure { error ->
            classicBluetoothActivationRequested = false
            Log.e(TAG, "Could not request glasses-initiated classic Bluetooth connection", error)
        }
    }

    // SN auto-generation: first attempt fails, we read the SN and retry
    private var snAutoRetryAttemptId: Long? = null
    // Generated snEncryptContent for retry (stored after first SN_CHECK_FAILED)
    private var generatedSnEncryptContent: ByteArray? = null

    // Callbacks for glasses events
    var onGlassesConnected: (() -> Unit)? = null
    var onGlassesDisconnected: (() -> Unit)? = null
    var onMessageFromGlasses: ((String, Caps?) -> Unit)? = null
    var onConnectionInfo: ((name: String, mac: String, account: String, type: Int) -> Unit)? = null
    var onBluetoothFailed: ((String) -> Unit)? = null

    // WiFi P2P callbacks
    var onWifiP2PConnected: (() -> Unit)? = null
    var onWifiP2PDisconnected: (() -> Unit)? = null
    var onWifiP2PFailed: (() -> Unit)? = null

    // APK installation callbacks
    var onApkUploadSucceed: (() -> Unit)? = null
    var onApkUploadFailed: (() -> Unit)? = null
    var onApkInstallSucceed: (() -> Unit)? = null
    var onApkInstallFailed: (() -> Unit)? = null

    // AI scene callbacks (voice input via glasses long-press)
    var onAiKeyDown: (() -> Unit)? = null
    var onAiKeyUp: (() -> Unit)? = null
    var onAiExit: (() -> Unit)? = null

    // Direct microphone PCM delivered by CXR. Newer Sprite firmware can keep the
    // custom CXR link alive while refusing Android HFP/SCO, so recognition must not
    // assume that a classic Bluetooth audio profile exists.
    var onAudioStreamStarted: ((codec: Int, originCodec: Int, channels: Int, streamType: String) -> Unit)? = null
    var onAudioStreamData: ((data: ByteArray, offset: Int, length: Int) -> Unit)? = null
    var onAudioStreamFinished: (() -> Unit)? = null

    // Photo capture callback
    var onPhotoResult: ((status: ValueUtil.CxrStatus?, photoBytes: ByteArray?) -> Unit)? = null

    private fun bluetoothCallback(attemptId: Long) = object : BluetoothStatusCallback {
        override fun onConnectionInfo(socketUuid: String?, macAddress: String?, rokidAccount: String?, deviceType: Int) {
            if (!connectionAttemptGate.isCurrent(attemptId)) {
                Log.i(TAG, "Ignoring stale CXR connection-info callback attempt=$attemptId")
                return
            }
            Log.i(TAG, "=== onConnectionInfo ===")
            Log.i(TAG, "  connection identifiers received")
            Log.i(TAG, "  deviceType=$deviceType")

            // Save for reconnection (both in memory and to SharedPreferences)
            savedSocketUuid = socketUuid
            savedMacAddress = macAddress
            savedRokidAccount = rokidAccount
            if (!socketUuid.isNullOrEmpty() && !macAddress.isNullOrEmpty()) {
                saveConnectionInfo(macAddress)
            }
            // Try to save device name from Bluetooth device
            try {
                val name = cxrApi?.let { api ->
                    val glassInfoField = api.javaClass.getDeclaredField("I")
                    glassInfoField.isAccessible = true
                    val glassInfo = glassInfoField.get(api)
                    glassInfo?.javaClass?.getMethod("getDeviceName")?.invoke(glassInfo) as? String
                }
                if (!name.isNullOrEmpty()) {
                    savedDeviceName = name
                    cachedDeviceName = name
                    saveDeviceName(name)
                    Log.i(TAG, "  deviceName=$name")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not read device name from GlassInfo: ${e.message}")
            }
            onConnectionInfo?.invoke(socketUuid ?: "", macAddress ?: "", rokidAccount ?: "", deviceType)

            // After initBluetooth, call connectBluetooth to complete the connection
            if (pendingConnectAttemptId == attemptId &&
                !socketUuid.isNullOrEmpty() && !macAddress.isNullOrEmpty()
            ) {
                Log.i(TAG, "Got connection info, now calling connectBluetooth...")
                pendingConnectAttemptId = null
                connectBluetoothInternal(attemptId, socketUuid, macAddress, rokidAccount ?: "")
            }
        }

        override fun onConnected() {
            if (!connectionAttemptGate.isCurrent(attemptId)) {
                Log.i(TAG, "Ignoring stale CXR connected callback attempt=$attemptId")
                return
            }
            Log.i(TAG, "=== onConnected === Bluetooth connected to glasses!")
            isBluetoothConnectedState = true
            pendingConnectAttemptId = null
            snAutoRetryAttemptId = null
            requestGlassesSoftwareVersions()
            syncAssistantInput()
            scheduleClassicBluetoothActivation()
            openGlassesApp()
            onGlassesConnected?.invoke()
        }

        override fun onInActiveConnected(socketUuid: String?, macAddress: String?) {
            if (!connectionAttemptGate.isCurrent(attemptId)) {
                Log.i(TAG, "Ignoring stale adopted-connection callback attempt=$attemptId")
                return
            }
            Log.i(TAG, "=== onInActiveConnected === Existing Bluetooth link adopted")
            if (!socketUuid.isNullOrEmpty() && socketUuid != "unknown" &&
                !macAddress.isNullOrEmpty() && macAddress != "unknown"
            ) {
                savedSocketUuid = socketUuid
                savedMacAddress = macAddress
                saveConnectionInfo(macAddress)
            }
            isBluetoothConnectedState = true
            pendingConnectAttemptId = null
            snAutoRetryAttemptId = null
            requestGlassesSoftwareVersions()
            syncAssistantInput()
            scheduleClassicBluetoothActivation()
            openGlassesApp()
            onGlassesConnected?.invoke()
        }

        override fun onDisconnected() {
            if (!connectionAttemptGate.isCurrent(attemptId)) {
                Log.i(TAG, "Ignoring stale CXR disconnected callback attempt=$attemptId")
                return
            }
            Log.i(TAG, "=== onDisconnected === Bluetooth disconnected from glasses")
            connectionAttemptGate.cancel()
            isBluetoothConnectedState = false
            classicBluetoothActivationRequested = false
            mainHandler.removeCallbacks(activateClassicBluetoothRunnable)
            mainHandler.removeCallbacks(reopenHudRunnable)
            hudForegroundRecovery.reset()
            onGlassesDisconnected?.invoke()
        }

        override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
            if (!connectionAttemptGate.isCurrent(attemptId)) {
                Log.i(TAG, "Ignoring stale CXR failure callback attempt=$attemptId")
                return
            }
            Log.e(TAG, "=== onFailed === Bluetooth connection failed: $errorCode")
            isBluetoothConnectedState = false
            classicBluetoothActivationRequested = false
            mainHandler.removeCallbacks(activateClassicBluetoothRunnable)
            pendingConnectAttemptId = null

            // SN_CHECK_FAILED means BT connected but SN verification failed.
            // Read the glasses SN from the SDK, generate encrypted content, and retry.
            if (errorCode == ValueUtil.CxrBluetoothErrorCode.SN_CHECK_FAILED &&
                snAutoRetryAttemptId != attemptId
            ) {
                Log.i(TAG, "SN_CHECK_FAILED - attempting auto-recovery...")
                val glassesSn = readGlassesSnFromSdk()
                if (glassesSn != null && glassesSn.isNotEmpty()) {
                    val clientSecret = configuredClientSecret()
                    if (clientSecret == null) {
                        snAutoRetryAttemptId = null
                        onBluetoothFailed?.invoke("Rokid credentials are missing or invalid")
                        return
                    }
                    val encrypted = generateSnEncryptContent(glassesSn, clientSecret)
                    if (encrypted != null) {
                        Log.i(TAG, "Generated SN verification content (${encrypted.size} bytes)")
                        generatedSnEncryptContent = encrypted
                        saveCachedSnEncryptContent(encrypted, glassesSn)
                        snAutoRetryAttemptId = attemptId
                        // Retry connection with correct snEncryptContent
                        val uuid = savedSocketUuid
                        val mac = savedMacAddress
                        if (!uuid.isNullOrEmpty() && !mac.isNullOrEmpty()) {
                            Log.i(TAG, "Retrying connectBluetooth with generated snEncryptContent...")
                            connectBluetoothInternal(attemptId, uuid, mac, savedRokidAccount ?: "")
                            return
                        }
                    }
                }
                Log.e(TAG, "SN auto-recovery failed - could not read glasses SN or generate encrypted content")
            }

            snAutoRetryAttemptId = null
            connectionAttemptGate.cancel()
            onBluetoothFailed?.invoke(errorCode?.name ?: "Unknown error")
        }
    }

    private val wifiP2PCallback = object : WifiP2PStatusCallback {
        override fun onConnected() {
            Log.i(TAG, "=== WiFi P2P onConnected === WiFi P2P link established!")
            isWifiP2PConnectedState = true
            onWifiP2PConnected?.invoke()
        }

        override fun onDisconnected() {
            Log.i(TAG, "=== WiFi P2P onDisconnected ===")
            isWifiP2PConnectedState = false
            onWifiP2PDisconnected?.invoke()
        }

        override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
            Log.e(TAG, "=== WiFi P2P onFailed === errorCode=$errorCode")
            isWifiP2PConnectedState = false
            onWifiP2PFailed?.invoke()
        }

        override fun onP2pDeviceAvailable(
            deviceName: String?,
            deviceAddress: String?,
            primaryDeviceType: String?,
        ) {
            Log.i(TAG, "=== WiFi P2P peer available === named=${!deviceName.isNullOrBlank()}")
            _capabilities.value = _capabilities.value.copy(p2pPeerAdvertised = true)
        }
    }

    private fun wifiHotCallback(attemptId: Long) = object : WifiHotStatusCallback {
        override fun onWifiHotAvailable(ssid: String?, password: String?, ip: String?, port: Int) {
            if (!hotspotAttemptGate.isActive(attemptId)) {
                Log.i(TAG, "Ignoring stale hotspot callback for attempt=$attemptId")
                return
            }
            Log.i(TAG, "Hotspot advertised for attempt=$attemptId; connecting without persisting credentials")
            if (ssid.isNullOrBlank() || password.isNullOrBlank() || ip.isNullOrBlank()) {
                failHotspotAttempt(attemptId, "Glasses hotspot response was incomplete")
                return
            }
            if (port != APK_UPLOAD_PORT) {
                Log.w(TAG, "Hotspot advertised unexpected port for attempt=$attemptId; SDK still requires $APK_UPLOAD_PORT")
            }
            _capabilities.value = _capabilities.value.copy(hotspotAdvertised = true)
            connectToGlassesHotspot(attemptId, ssid, password, ip)
        }
    }

    @SuppressLint("MissingPermission", "WrongConstant")
    private fun connectToGlassesHotspot(attemptId: Long, ssid: String, password: String, ip: String) {
        when (hotspotAttemptGate.registerAdvertisement(attemptId, ssid, ip)) {
            HotspotAttemptGate.AdvertisementDecision.IGNORE_STALE -> {
                Log.i(TAG, "Ignoring stale hotspot advertisement for attempt=$attemptId")
                return
            }
            HotspotAttemptGate.AdvertisementDecision.IGNORE_DUPLICATE -> {
                Log.i(TAG, "Ignoring duplicate hotspot advertisement for attempt=$attemptId")
                return
            }
            HotspotAttemptGate.AdvertisementDecision.START_CONNECTION -> Unit
        }

        val context = appContext ?: run {
            failHotspotAttempt(attemptId, "Application context is unavailable")
            return
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            failHotspotAttempt(attemptId, "Glasses hotspot requires Android 10 or newer")
            return
        }

        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        hotspotNetworkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        synchronized(hotspotAttemptLock) {
            activeHotspotAttempt
                ?.takeIf { it.id == attemptId }
                ?.let { attempt ->
                    attempt.probeJob?.cancel()
                    attempt.probeJob = null
                    attempt.probeStarted = false
                }
        }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val requestBuilder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
        }
        val request = requestBuilder.build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!hotspotAttemptGate.isActive(attemptId)) return
                Log.i(TAG, "Android hotspot network available for attempt=$attemptId; probing upload service")
                probeHotspotUploadService(attemptId, connectivityManager, network, ip)
            }

            override fun onLost(network: Network) {
                if (!hotspotAttemptGate.isActive(attemptId)) return
                Log.i(TAG, "Glasses hotspot disconnected for attempt=$attemptId")
                failHotspotAttempt(attemptId, "Glasses hotspot disconnected before upload started")
            }

            override fun onUnavailable() {
                if (!hotspotAttemptGate.isActive(attemptId)) return
                failHotspotAttempt(attemptId, "Glasses hotspot connection unavailable")
            }
        }
        hotspotNetworkCallback = callback
        runCatching {
            connectivityManager.requestNetwork(request, callback, HOTSPOT_CONNECT_TIMEOUT_MS)
        }.onFailure { error ->
            failHotspotAttempt(attemptId, "Could not request glasses hotspot network: ${error.message}")
        }
    }

    private fun probeHotspotUploadService(
        attemptId: Long,
        connectivityManager: ConnectivityManager,
        network: Network,
        ipAddress: String,
    ) {
        val shouldProbe = synchronized(hotspotAttemptLock) {
            val attempt = activeHotspotAttempt
            if (attempt == null || attempt.id != attemptId || attempt.probeStarted) {
                false
            } else {
                attempt.probeStarted = true
                true
            }
        }
        if (!shouldProbe) return

        val probeJob = hotspotScope.launch {
            repeat(HOTSPOT_PROBE_ATTEMPTS) { probeIndex ->
                if (!hotspotAttemptGate.isActive(attemptId)) return@launch
                val reachable = runCatching {
                    network.socketFactory.createSocket().use { socket ->
                        socket.connect(
                            InetSocketAddress(ipAddress, APK_UPLOAD_PORT),
                            HOTSPOT_PROBE_CONNECT_TIMEOUT_MS,
                        )
                    }
                }.isSuccess
                if (reachable) {
                    if (!connectivityManager.bindProcessToNetwork(network)) {
                        failHotspotAttempt(attemptId, "Could not bind APK upload to glasses hotspot")
                        return@launch
                    }
                    val completion = synchronized(hotspotAttemptLock) {
                        activeHotspotAttempt
                            ?.takeIf { it.id == attemptId }
                            ?.completion
                    }
                    completion?.complete(GlassesHotspotConnection(attemptId, ipAddress))
                    Log.i(TAG, "Hotspot upload service reachable for attempt=$attemptId; network bound")
                    return@launch
                }
                if (probeIndex < HOTSPOT_PROBE_ATTEMPTS - 1) {
                    delay(HOTSPOT_PROBE_RETRY_DELAY_MS)
                }
            }
            failHotspotAttempt(attemptId, "Glasses upload service is not reachable on port $APK_UPLOAD_PORT")
        }
        synchronized(hotspotAttemptLock) {
            activeHotspotAttempt
                ?.takeIf { it.id == attemptId }
                ?.probeJob = probeJob
        }
    }

    private fun failHotspotAttempt(attemptId: Long, message: String) {
        val completion = synchronized(hotspotAttemptLock) {
            activeHotspotAttempt
                ?.takeIf { it.id == attemptId }
                ?.completion
        }
        if (completion?.completeExceptionally(IllegalStateException(message)) == true) {
            Log.e(TAG, "$message (attempt=$attemptId)")
        }
    }

    private val apkCallback = object : ApkStatusCallback {
        override fun onUploadApkSucceed() {
            Log.d(TAG, "APK upload succeeded")
            onApkUploadSucceed?.invoke()
        }

        override fun onUploadApkFailed() {
            Log.e(TAG, "APK upload failed")
            onApkUploadFailed?.invoke()
        }

        override fun onInstallApkSucceed() {
            Log.d(TAG, "APK installation succeeded")
            openGlassesApp()
            onApkInstallSucceed?.invoke()
        }

        override fun onInstallApkFailed() {
            Log.e(TAG, "APK installation failed")
            onApkInstallFailed?.invoke()
        }

        override fun onUninstallApkSucceed() {
            Log.d(TAG, "APK uninstall succeeded")
        }

        override fun onUninstallApkFailed() {
            Log.e(TAG, "APK uninstall failed")
        }

        override fun onOpenAppSucceed() {
            Log.d(TAG, "App opened successfully")
        }

        override fun onOpenAppFailed() {
            Log.e(TAG, "Failed to open app")
        }

        override fun onStopAppResult(success: Boolean) {
            Log.d(TAG, "Stop app result: success=$success")
        }

        override fun onGlassAppResume(packageName: String?) {
            Log.i(TAG, "Glasses foreground app: ${packageName ?: "unknown"}")
            when (hudForegroundRecovery.onForegroundChanged(
                packageName = packageName,
                connected = isConnected(),
                nowMs = SystemClock.elapsedRealtime(),
            )) {
                HudForegroundRecovery.ForegroundAction.SCHEDULE_RECOVERY -> {
                    Log.i(TAG, "Clawsses AI scene reached launcher; scheduling one HUD recovery")
                    mainHandler.removeCallbacks(reopenHudRunnable)
                    mainHandler.postDelayed(reopenHudRunnable, HUD_RECOVERY_DELAY_MS)
                }

                HudForegroundRecovery.ForegroundAction.CANCEL_RECOVERY -> {
                    mainHandler.removeCallbacks(reopenHudRunnable)
                }

                HudForegroundRecovery.ForegroundAction.NONE -> Unit
            }
        }

        override fun onQueryAppResult(packageName: String?, installed: Boolean) {
            Log.d(TAG, "Glasses app query result: installed=$installed")
        }

    }

    /**
     * Initialize the CxrApi singleton and set up listeners.
     * Registers the access key for SN verification during Bluetooth connection.
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) {
            Log.d(TAG, "SDK already initialized")
            return true
        }

        appContext = context.applicationContext

        // Load cached SN encrypt content from previous session
        loadCachedSnEncryptContent()

        // Load saved connection info for auto-reconnect
        loadSavedConnectionInfo()

        try {
            cxrApi = CxrApi.getInstance()

            // Register access key for SN verification (required for connectBluetooth)
            val accessKey = BuildConfig.ROKID_ACCESS_KEY
            if (accessKey.isNotEmpty()) {
                cxrApi?.updateRokidAccount(accessKey)
                Log.d(TAG, "Rokid account registered")
            } else {
                Log.w(TAG, "No ROKID_ACCESS_KEY configured - SN verification may fail")
            }

            // Set up custom command listener to receive messages from glasses
            // The glasses sends via bridge.sendMessage(msgType, caps) where caps contains the actual data.
            // Here, cmd = the message type (e.g. "command"), and caps holds the content string at index 0.
            cxrApi?.setCustomCmdListener(object : CustomCmdListener {
                override fun onCustomCmd(cmd: String?, caps: Caps?) {
                    Log.d(TAG, "Received custom command from glasses: type=$cmd, caps=${caps != null}")
                    if (caps != null && caps.size() > 0) {
                        try {
                            val message = caps.at(0).getString()
                            Log.d(TAG, "Glasses message received (${message.length} chars)")
                            onMessageFromGlasses?.invoke(message, caps)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read message from Caps", e)
                            cmd?.let { onMessageFromGlasses?.invoke(it, caps) }
                        }
                    } else {
                        cmd?.let { onMessageFromGlasses?.invoke(it, caps) }
                    }
                }
            })

            // Set up AI event listener for glasses long-press voice activation
            cxrApi?.setAiEventListener(object : com.rokid.cxr.client.extend.listeners.AiEventListener {
                override fun onAiKeyDown() {
                    aiSceneRunning = true
                    Log.i(TAG, "AI key pressed on glasses (long press)")
                    dispatchAiActivation("key")
                }
                override fun onAiKeyUp() {
                    Log.d(TAG, "AI key released on glasses")
                    onAiKeyUp?.invoke()
                }
                override fun onAiExit() {
                    aiSceneRunning = false
                    Log.d(TAG, "AI scene exited on glasses")
                    onAiExit?.invoke()
                }
            })

            // Newer Sprite firmware can omit AiEventListener.onAiKeyDown while still
            // publishing the AI scene transition. Treat the rising scene edge as the
            // activation event and retain AiEventListener as the legacy fallback.
            cxrApi?.setSceneStatusUpdateListener(object : SceneStatusUpdateListener {
                override fun onSceneStatusUpdated(sceneStatusInfo: SceneStatusInfo?) {
                    val running = sceneStatusInfo?.let {
                        it.isAiAssistRunning || it.isAiChatRunning
                    } ?: false
                    val started = running && !aiSceneRunning
                    aiSceneRunning = running
                    Log.d(TAG, "AI scene status updated: running=$running")
                    if (started) {
                        Log.i(TAG, "AI activation detected from scene status")
                        dispatchAiActivation("scene")
                    }
                }
            })

            cxrApi?.setAudioStreamListener(object : AudioStreamListener {
                override fun onStartAudioStream(
                    codecType: Int,
                    originCodec: Int,
                    channels: Int,
                    streamType: String?,
                ) {
                    Log.i(
                        TAG,
                        "Glasses microphone stream started: codec=$codecType, originCodec=$originCodec, channels=$channels",
                    )
                    onAudioStreamStarted?.invoke(
                        codecType,
                        originCodec,
                        channels,
                        streamType.orEmpty(),
                    )
                }

                override fun onAudioStream(
                    streamId: Int,
                    data: ByteArray?,
                    offset: Int,
                    length: Int,
                ) {
                    if (data != null && length > 0 && offset >= 0 && offset + length <= data.size) {
                        onAudioStreamData?.invoke(data, offset, length)
                    }
                }

                override fun onAudioStreamFinish(streamId: Int) {
                    Log.i(TAG, "Glasses microphone stream finished")
                    onAudioStreamFinished?.invoke()
                }
            })

            // Track glasses brightness changes so we can restore the user's
            // preferred level when waking the display from standby.
            cxrApi?.setBrightnessUpdateListener(object : BrightnessUpdateListener {
                override fun onBrightnessUpdated(brightness: Int) {
                    Log.d(TAG, "Glasses brightness updated: $brightness")
                    lastKnownBrightness = brightness
                }
            })

            Log.d(TAG, "Rokid SDK initialized successfully")
            isInitialized = true
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Rokid SDK", e)
            return false
        }
    }

    private fun dispatchAiActivation(source: String) {
        val nowMs = SystemClock.elapsedRealtime()
        if (!aiActivationGate.tryAccept(nowMs)) {
            Log.i(TAG, "Ignoring duplicate AI activation source=$source")
            return
        }
        onAiKeyDown?.invoke()
    }

    /**
     * Initialize Bluetooth connection with a discovered device.
     * This triggers onConnectionInfo callback, then we automatically call
     * connectBluetooth to complete the connection.
     */
    fun initBluetooth(device: BluetoothDevice) {
        val context = appContext ?: run {
            Log.e(TAG, "SDK not initialized")
            return
        }

        try {
            val attemptId = connectionAttemptGate.begin()
            Log.i(TAG, "=== initBluetooth === Starting attempt=$attemptId")
            pendingConnectAttemptId = attemptId
            snAutoRetryAttemptId = null
            cxrApi?.initBluetooth(context, device, bluetoothCallback(attemptId))
            Log.i(TAG, "initBluetooth called, waiting for onConnectionInfo callback...")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Bluetooth", e)
            pendingConnectAttemptId = null
        }
    }

    /**
     * Connect using socketUuid and macAddress from onConnectionInfo.
     *
     * SDK signature: connectBluetooth(context, socketUuid, macAddress, callback, snEncryptContent, clientSecret)
     *
     * The SDK performs an SN verification after BT connects:
     * 1. Gets glasses SN via getGlassInfo
     * 2. Decrypts snEncryptContent with clientSecret (AES/CBC/PKCS5Padding)
     * 3. Checks if decrypted text contains the glasses SN
     *
     * On first connect we pass empty snEncryptContent, which triggers SN_CHECK_FAILED.
     * The onFailed handler then reads the SN via reflection and auto-retries with
     * correctly generated encrypted content.
     */
    private fun connectBluetoothInternal(
        attemptId: Long,
        socketUuid: String,
        macAddress: String,
        rokidAccount: String = "",
    ) {
        if (!connectionAttemptGate.isCurrent(attemptId)) {
            Log.i(TAG, "Ignoring stale connectBluetooth request attempt=$attemptId")
            return
        }
        val context = appContext ?: run {
            Log.e(TAG, "SDK not initialized")
            return
        }

        try {
            val clientSecret = configuredClientSecret() ?: run {
                onBluetoothFailed?.invoke("Rokid credentials are missing or invalid")
                return
            }

            Log.i(TAG, "=== connectBluetoothInternal ===")
            Log.i(TAG, "  connection identifiers available")
            Log.i(TAG, "  snRetry=${snAutoRetryAttemptId == attemptId}, cachedSn=${generatedSnEncryptContent != null}")

            // Use cached snEncryptContent if available (from previous SN auto-recovery).
            // Only use dummy content on the very first connection attempt when we don't
            // know the glasses SN yet. This avoids a redundant two-pass flow on reconnects.
            val encryptContent = if (generatedSnEncryptContent != null) {
                Log.i(TAG, "Using cached snEncryptContent (${generatedSnEncryptContent!!.size} bytes)")
                generatedSnEncryptContent!!
            } else {
                Log.i(TAG, "First attempt - using dummy snEncryptContent (SN_CHECK_FAILED expected)")
                ByteArray(16)
            }

            cxrApi?.connectBluetooth(
                context,
                socketUuid,
                macAddress,
                rokidAccount,
                bluetoothCallback(attemptId),
                encryptContent,
                clientSecret
            )
            Log.i(TAG, "connectBluetooth called, waiting for callback...")
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting via Bluetooth", e)
        }
    }

    /**
     * Connect to glasses via Bluetooth using saved connection info.
     */
    fun connectBluetooth(socketUuid: String, macAddress: String) {
        val account = savedRokidAccount
        if (socketUuid != savedSocketUuid || account == null) {
            Log.w(TAG, "Refusing direct reconnect with identifiers outside the current process session")
            return
        }
        val attemptId = connectionAttemptGate.begin()
        pendingConnectAttemptId = null
        snAutoRetryAttemptId = null
        connectBluetoothInternal(attemptId, socketUuid, macAddress, account)
    }

    // ============== SN Auto-Generation Helpers ==============

    /**
     * Read the glasses serial number from CxrApi's internal GlassInfo field (field I).
     * The SDK populates this in the getGlassInfo response handler, which runs
     * before the SN check — so even on SN_CHECK_FAILED, the SN is available.
     */
    private fun readGlassesSnFromSdk(): String? {
        return try {
            val api = cxrApi ?: return null
            // CxrApi stores GlassInfo in field 'I'
            val glassInfoField = api.javaClass.getDeclaredField("I")
            glassInfoField.isAccessible = true
            val glassInfo = glassInfoField.get(api) ?: run {
                Log.w(TAG, "GlassInfo field I is null")
                return null
            }
            // GlassInfo.getDeviceId() returns the serial number
            val getDeviceId = glassInfo.javaClass.getMethod("getDeviceId")
            val sn = getDeviceId.invoke(glassInfo) as? String
            Log.i(TAG, "Read glasses serial number from SDK")
            sn
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read glasses SN from SDK via reflection", e)
            null
        }
    }

    /**
     * Log firmware compatibility evidence without exposing device identifiers or credentials.
     */
    private fun requestGlassesSoftwareVersions() {
        _capabilities.value = GlassesCapabilitySnapshot()
        val status = cxrApi?.getGlassInfo(object : GlassInfoResultCallback {
            override fun onGlassInfoResult(status: ValueUtil.CxrStatus?, glassInfo: GlassInfo?) {
                val systemVersion = glassInfo?.systemVersion?.takeIf(String::isNotBlank)
                val assistVersion = glassInfo?.assistVersionName?.takeIf(String::isNotBlank)
                _capabilities.value = _capabilities.value.copy(
                    systemVersion = systemVersion,
                    assistantVersion = assistVersion,
                    glassInfoAvailable = status == ValueUtil.CxrStatus.REQUEST_SUCCEED && glassInfo != null,
                )
                Log.i(
                    TAG,
                    "Glasses software: status=$status, " +
                        "support=${GlassesCapabilityPolicy.firmwareSupport(_capabilities.value)}",
                )
            }
        })
        Log.d(TAG, "Glasses software version request: $status")
        val versionStatus = cxrApi?.checkGlassVersion(object : GlassVersionCallback {
            override fun onGlassVersion(passed: Boolean, message: String?) {
                _capabilities.value = _capabilities.value.copy(sdkVersionCheckPassed = passed)
                Log.i(TAG, "Glasses SDK version check completed: passed=$passed")
            }
        })
        Log.d(TAG, "Glasses SDK version check request: $versionStatus")
    }

    /**
     * Keep the firmware-owned wake word and AI-key event source enabled.
     *
     * CXR-M only registers [AiEventListener]; it does not implicitly enable the
     * persisted glasses setting that produces those events. Newer Sprite
     * firmware may retain `settings_voice_control=close` across updates or
     * companion-app changes, leaving the HUD transport alive while neither the
     * wake word nor the AI key reaches Clawsses. The firmware accepts the
     * literal values `open` and `close`, so reapplying `open` on every adopted
     * Bluetooth session is idempotent and reversible from the glasses settings.
     */
    fun syncAssistantInput(): Boolean {
        if (!isInitialized || !isBluetoothConnectedState) {
            Log.d(TAG, "Glasses assistant input sync deferred: CXR link not ready")
            return false
        }
        val status = cxrApi?.setVoiceCtrl("open")
        Log.i(TAG, "Glasses assistant input enable request: $status")
        return status == ValueUtil.CxrStatus.REQUEST_SUCCEED
    }

    /**
     * Ask Sprite firmware to initiate its classic phone/audio profiles itself.
     *
     * Firmware 1.24 rejects Pixel-initiated HFP/A2DP immediately while keeping
     * the CXR control socket alive. CXR-M's parameterless `activeBluetoothConnect`
     * command targets the currently paired phone ("self") and does not mutate
     * pairing records. Delay it until the authenticated CXR socket is settled,
     * and issue it only once per connected session.
     */
    private fun scheduleClassicBluetoothActivation() {
        mainHandler.removeCallbacks(activateClassicBluetoothRunnable)
        if (!classicBluetoothActivationRequested) {
            mainHandler.postDelayed(
                activateClassicBluetoothRunnable,
                CLASSIC_BLUETOOTH_ACTIVATION_DELAY_MS,
            )
        }
    }

    /** Refresh capability evidence before selecting a firmware-sensitive transport. */
    fun refreshGlassesCapabilities() {
        if (!isInitialized || !isBluetoothConnectedState) return
        requestGlassesSoftwareVersions()
    }

    /**
     * Generate snEncryptContent by encrypting the glasses SN using the same
     * algorithm the SDK uses for verification: AES/CBC/PKCS5Padding.
     *
     * Key = clientSecret bytes (32 chars = 32 bytes for AES-256)
     * IV = first 16 bytes of clientSecret
     */
    private fun generateSnEncryptContent(glassesSn: String, clientSecret: String): ByteArray? {
        return try {
            val keyBytes = clientSecret.toByteArray(Charsets.UTF_8)
            val key = SecretKeySpec(keyBytes, "AES")
            val iv = IvParameterSpec(keyBytes, 0, 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key, iv)
            cipher.doFinal(glassesSn.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate snEncryptContent", e)
            null
        }
    }

    private fun configuredClientSecret(): String? {
        val normalized = BuildConfig.ROKID_CLIENT_SECRET.replace("-", "")
        if (normalized.length != 32) {
            Log.e(TAG, "Rokid client credential is missing or invalid")
            return null
        }
        return normalized
    }


    // ============== SN Persistence ==============

    private const val SN_PREFS = "clawsses_glasses_sn"
    private const val SN_KEY = "sn_encrypt_content"
    private const val SN_PLAIN_KEY = "sn_plain"
    private const val DEVICE_NAME_KEY = "device_name"
    private const val LEGACY_SOCKET_UUID_KEY = "socket_uuid"
    private const val MAC_ADDRESS_KEY = "mac_address"
    private var cachedSnPlain: String? = null
    private var cachedDeviceName: String? = null

    private fun saveCachedSnEncryptContent(encrypted: ByteArray, plainSn: String? = null) {
        val ctx = appContext ?: return
        val base64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        SecurePreferences.create(ctx, SN_PREFS)
            .edit()
            .putString(SN_KEY, base64)
            .apply {
                if (plainSn != null) {
                    putString(SN_PLAIN_KEY, plainSn)
                    cachedSnPlain = plainSn
                }
            }
            .apply()
        Log.i(TAG, "Saved SN encrypt content to SharedPreferences")
    }

    private fun loadCachedSnEncryptContent() {
        val ctx = appContext ?: return
        val prefs = SecurePreferences.create(ctx, SN_PREFS)
        val base64 = prefs.getString(SN_KEY, null) ?: return
        try {
            generatedSnEncryptContent = Base64.decode(base64, Base64.NO_WRAP)
            cachedSnPlain = prefs.getString(SN_PLAIN_KEY, null)
            cachedDeviceName = prefs.getString(DEVICE_NAME_KEY, null)
            Log.i(TAG, "Loaded cached SN encrypt content (${generatedSnEncryptContent!!.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached SN encrypt content", e)
        }
    }

    private fun saveDeviceName(name: String) {
        val ctx = appContext ?: return
        SecurePreferences.create(ctx, SN_PREFS)
            .edit()
            .putString(DEVICE_NAME_KEY, name)
            .apply()
    }

    /**
     * Persist only the bonded device address for auto-reconnection.
     *
     * CXR socket UUIDs belong to one firmware session and rotate after reboot or GlassRemoveBond.
     * The current UUID remains in memory for the same handshake and SN retry, but must never become
     * a cold-start reconnect credential.
     * Called after successful Bluetooth connection.
     */
    private fun saveConnectionInfo(macAddress: String) {
        val ctx = appContext ?: return
        SecurePreferences.create(ctx, SN_PREFS)
            .edit()
            .putString(MAC_ADDRESS_KEY, macAddress)
            .remove(LEGACY_SOCKET_UUID_KEY)
            .apply()
        Log.i(TAG, "Saved bonded device address for full CXR rediscovery")
    }

    /**
     * Load the bonded device address from SharedPreferences.
     * Called during SDK initialization.
     */
    private fun loadSavedConnectionInfo() {
        val ctx = appContext ?: return
        val prefs = SecurePreferences.create(ctx, SN_PREFS)
        savedSocketUuid = null
        savedMacAddress = prefs.getString(MAC_ADDRESS_KEY, null)
        prefs.edit().remove(LEGACY_SOCKET_UUID_KEY).apply()
        if (savedMacAddress != null) {
            Log.i(TAG, "Loaded bonded device address for full CXR rediscovery")
        }
    }

    /**
     * Check if we have saved connection info for auto-reconnection.
     */
    fun hasSavedConnectionInfo(): Boolean {
        return !savedMacAddress.isNullOrEmpty()
    }

    /**
     * Clear the cached glasses serial number and connection info.
     * Call this if connecting to different glasses or if SN verification fails persistently.
     */
    fun clearCachedSn() {
        generatedSnEncryptContent = null
        cachedSnPlain = null
        cachedDeviceName = null
        savedSocketUuid = null
        savedMacAddress = null
        val ctx = appContext ?: return
        SecurePreferences.create(ctx, SN_PREFS)
            .edit()
            .remove(SN_KEY)
            .remove(SN_PLAIN_KEY)
            .remove(DEVICE_NAME_KEY)
            .remove(LEGACY_SOCKET_UUID_KEY)
            .remove(MAC_ADDRESS_KEY)
            .apply()
        Log.i(TAG, "Cleared cached SN and connection info")
    }

    /**
     * Check whether a cached glasses SN exists.
     */
    fun hasCachedSn(): Boolean = generatedSnEncryptContent != null

    /**
     * Get the cached plain-text glasses serial number, if available.
     */
    fun getCachedSn(): String? = cachedSnPlain

    /**
     * Get the cached device name (e.g., "Rokid Max 2"), if available.
     */
    fun getCachedDeviceName(): String? = cachedDeviceName

    /**
     * Send a custom command/message to the glasses via Bluetooth
     */
    fun sendToGlasses(command: String, caps: Caps = Caps()): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return false
        }

        if (!isBluetoothConnectedState) {
            Log.e(TAG, "Not connected to glasses via Bluetooth")
            return false
        }

        val payloadBytes = CxrPayloadLimits.byteSize(command)
        if (payloadBytes > CxrPayloadLimits.MAX_BYTES) {
            Log.e(TAG, "Refusing oversized glasses message ($payloadBytes bytes)")
            return false
        }

        return try {
            caps.write(command)
            cxrApi?.sendCustomCmd("terminal", caps)
            Log.d(TAG, "Sent to glasses ($payloadBytes bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message to glasses", e)
            false
        }
    }

    // ============== WiFi P2P Methods ==============

    /**
     * Initialize WiFi P2P connection for data transfer (APK uploads, etc.)
     * Call this after Bluetooth is connected.
     */
    fun initWifiP2P(): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return false
        }

        if (!isBluetoothConnectedState) {
            Log.e(TAG, "Bluetooth not connected - connect via Bluetooth first")
            return false
        }

        return try {
            val status = cxrApi?.initWifiP2P2(true, wifiP2PCallback)
            Log.d(TAG, "WiFi P2P initialization status: $status")
            status == ValueUtil.CxrStatus.REQUEST_SUCCEED
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WiFi P2P", e)
            false
        }
    }

    /**
     * Deinitialize WiFi P2P connection
     */
    @SuppressLint("MissingPermission")
    fun deinitWifiP2P() {
        try {
            val wasConnected = isWifiP2PConnectedState
            cxrApi?.deinitWifiP2P()
            isWifiP2PConnectedState = false
            Log.d(TAG, "WiFi P2P deinitialized")
            // SDK doesn't fire wifiP2PCallback.onDisconnected() on programmatic teardown,
            // so notify listeners explicitly to keep UI state in sync.
            if (wasConnected) {
                onWifiP2PDisconnected?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deinitializing WiFi P2P", e)
        }
    }

    /**
     * Check if WiFi P2P is connected
     */
    fun isWifiP2PConnected(): Boolean {
        return try {
            cxrApi?.isWifiP2PConnected ?: false
        } catch (e: Exception) {
            isWifiP2PConnectedState
        }
    }

    suspend fun awaitWifiHotspotConnection(timeoutMs: Long): GlassesHotspotConnection {
        val attempt = withContext(Dispatchers.Main.immediate) {
            check(isInitialized && isBluetoothConnectedState) {
                "Rokid SDK or Bluetooth connection is not ready"
            }
            val replacedAttempt = synchronized(hotspotAttemptLock) { activeHotspotAttempt?.id }
            if (replacedAttempt != null) {
                Log.w(TAG, "Replacing unfinished hotspot attempt=$replacedAttempt")
                cancelHotspotAttempt(replacedAttempt)
            }
            val attemptId = hotspotAttemptGate.begin()
            val active = ActiveHotspotAttempt(
                id = attemptId,
                completion = CompletableDeferred(),
            )
            synchronized(hotspotAttemptLock) {
                activeHotspotAttempt = active
            }
            val status = runCatching {
                cxrApi?.initWifiHot(wifiHotCallback(attemptId))
            }.getOrElse { error ->
                failHotspotAttempt(attemptId, "Error initializing glasses hotspot: ${error.message}")
                null
            }
            Log.i(TAG, "Hotspot attempt=$attemptId initialization status: $status")
            if (status != ValueUtil.CxrStatus.REQUEST_SUCCEED) {
                failHotspotAttempt(attemptId, "Glasses rejected hotspot initialization")
            }
            active
        }

        return try {
            withTimeout(timeoutMs) { attempt.completion.await() }
        } finally {
            if (!attempt.completion.isCompleted) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    cancelHotspotAttempt(attempt.id)
                }
            }
        }
    }

    private fun cancelHotspotAttempt(attemptId: Long) {
        val completion = synchronized(hotspotAttemptLock) {
            val active = activeHotspotAttempt?.takeIf { it.id == attemptId } ?: return
            active.probeJob?.cancel()
            activeHotspotAttempt = null
            hotspotAttemptGate.end(attemptId)
            active.completion
        }
        completion.cancel()
    }

    fun deinitWifiHotspot() {
        val activeAttemptId = synchronized(hotspotAttemptLock) { activeHotspotAttempt?.id }
        if (activeAttemptId != null) cancelHotspotAttempt(activeAttemptId)
        val context = appContext
        if (context != null) {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.bindProcessToNetwork(null)
            hotspotNetworkCallback?.let { callback ->
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
        }
        hotspotNetworkCallback = null
        runCatching { cxrApi?.deinitWifiHot() }
        Log.i(TAG, "Glasses hotspot transport released")
    }

    // ============== APK Installation Methods ==============

    /**
     * Upload and install an APK on the glasses via WiFi P2P.
     * Requires: Bluetooth connected AND WiFi P2P connected
     */
    fun startUploadApk(apkPath: String): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "SDK not initialized")
            return false
        }

        if (!isBluetoothConnectedState) {
            Log.e(TAG, "Bluetooth not connected")
            return false
        }

        return try {
            val result = cxrApi?.startUploadApk(apkPath, apkCallback) ?: false
            Log.d(TAG, "startUploadApk result: $result for path: $apkPath")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error starting APK upload", e)
            false
        }
    }

    fun startUploadApk(apkPath: String, glassesIp: String): Boolean {
        if (!isInitialized || !isBluetoothConnectedState || glassesIp.isBlank()) return false
        return try {
            val result = cxrApi?.startUploadApk(apkPath, glassesIp, apkCallback) ?: false
            Log.d(TAG, "startUploadApk via glasses hotspot result: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error starting APK upload via glasses hotspot", e)
            false
        }
    }

    /**
     * Cancel an ongoing APK upload
     */
    fun stopUploadApk() {
        try {
            cxrApi?.stopUploadApk()
            Log.d(TAG, "APK upload stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping APK upload", e)
        }
    }

    /**
     * Launch the Clawsses HUD after installation or when reconnecting to glasses.
     */
    fun openGlassesApp(): Boolean {
        if (!isInitialized || !isBluetoothConnectedState) {
            Log.e(TAG, "Cannot open glasses app: SDK or Bluetooth is not ready")
            return false
        }

        return try {
            val status = cxrApi?.openApp(
                RKAppInfo(GLASSES_APP_PACKAGE, GLASSES_APP_ACTIVITY),
                apkCallback
            )
            Log.d(TAG, "Open glasses app request status: $status")
            status == ValueUtil.CxrStatus.REQUEST_SUCCEED
        } catch (e: Exception) {
            Log.e(TAG, "Error opening glasses app", e)
            false
        }
    }

    /**
     * Ask the connected Sprite firmware to perform a full glasses reboot.
     *
     * This uses CXR-M's official reboot command. A successful return value means
     * the firmware accepted the request; the Bluetooth/CXR disconnect happens
     * asynchronously as the glasses shut down.
     */
    fun restartGlasses(): Boolean {
        if (!isInitialized || !isConnected()) {
            Log.w(TAG, "Cannot restart glasses: CXR link is not ready")
            return false
        }

        return try {
            val status = cxrApi?.notifyGlassReboot()
            Log.i(TAG, "Glasses reboot request status: $status")
            status == ValueUtil.CxrStatus.REQUEST_SUCCEED
        } catch (error: Exception) {
            Log.e(TAG, "Could not request glasses reboot", error)
            false
        }
    }

    // ============== Status Methods ==============

    fun isReady(): Boolean = isInitialized

    fun isConnected(): Boolean {
        return isBluetoothConnectedState || try {
            cxrApi?.isBluetoothConnected ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun getSavedMacAddress(): String? = savedMacAddress
    fun getSavedDeviceName(): String? = savedDeviceName
    fun getSavedSocketUuid(): String? = savedSocketUuid

    /**
     * Attempt a full CXR rediscovery for the bonded glasses.
     *
     * The firmware rotates its socket UUID after reboot and GlassRemoveBond, so reconnect never
     * reuses a persisted UUID. Every attempt obtains fresh identifiers through initBluetooth.
     */
    fun reconnect(attempt: Int = 1): Boolean {
        if (isConnected()) {
            Log.i(TAG, "Reconnect attempt $attempt skipped: glasses are already connected")
            return true
        }

        val mac = savedMacAddress
        if (mac.isNullOrEmpty()) {
            Log.w(TAG, "No saved MAC address for reconnection")
            return false
        }

        if (cxrApi == null) {
            Log.e(TAG, "CxrApi is null — cannot reconnect")
            return false
        }

        val context = appContext ?: run {
            Log.e(TAG, "SDK not initialized — cannot reconnect")
            return false
        }

        return try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                Log.w(TAG, "Reconnect attempt $attempt: Bluetooth adapter unavailable")
                false
            } else {
                val device = adapter.getRemoteDevice(mac)
                Log.i(TAG, "Reconnect attempt $attempt: starting full CXR rediscovery")
                initBluetooth(device)
                true
            }
        } catch (error: Exception) {
            Log.w(TAG, "Bluetooth initialization failed during reconnect", error)
            false
        }
    }

    /**
     * Disconnect from glasses
     */
    fun disconnect() {
        try {
            connectionAttemptGate.cancel()
            pendingConnectAttemptId = null
            snAutoRetryAttemptId = null
            mainHandler.removeCallbacks(activateClassicBluetoothRunnable)
            classicBluetoothActivationRequested = false
            deinitWifiP2P()
            cxrApi?.deinitBluetooth()
            isBluetoothConnectedState = false
            Log.d(TAG, "Disconnected from glasses")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    /**
     * Set audio as communication device (for voice input via glasses mic)
     */
    fun setCommunicationDevice() {
        cxrApi?.setCommunicationDevice()
    }

    /**
     * Clear communication device setting
     */
    fun clearCommunicationDevice() {
        cxrApi?.clearCommunicationDevice()
    }

    /**
     * Request 24 kHz mono PCM directly from the glasses over CXR.
     * This is independent of Android's HFP/SCO profile and is the preferred input
     * path on Sprite firmware that rejects classic Bluetooth audio connections.
     */
    fun startMicrophoneStream(): Boolean {
        if (!isConnected()) return false
        val status = cxrApi?.openAudioRecord(
            DIRECT_AUDIO_CODEC_PCM,
            DIRECT_AUDIO_RECORD_MODE,
            DIRECT_AUDIO_STREAM_TYPE,
        )
        Log.i(TAG, "Glasses microphone stream request: $status")
        return status == ValueUtil.CxrStatus.REQUEST_SUCCEED
    }

    fun stopMicrophoneStream() {
        if (isConnected()) {
            val status = cxrApi?.closeAudioRecord(DIRECT_AUDIO_STREAM_TYPE)
            Log.d(TAG, "Glasses microphone stop request: $status")
        }
    }

    fun clearMicrophoneStreamCallbacks() {
        onAudioStreamStarted = null
        onAudioStreamData = null
        onAudioStreamFinished = null
    }

    // --- AI Scene methods (for voice input via glasses long-press) ---

    /**
     * Send ASR (speech recognition) content to the glasses AI scene.
     * The glasses display this text in the AI scene UI.
     */
    fun sendAsrContent(content: String): ValueUtil.CxrStatus? {
        Log.d(TAG, "Sending ASR content to glasses (${content.length} chars)")
        return cxrApi?.sendAsrContent(content)
    }

    /**
     * Notify glasses that ASR recognition returned no result.
     */
    fun notifyAsrNone(): ValueUtil.CxrStatus? {
        Log.d(TAG, "Notifying glasses: ASR none")
        return cxrApi?.notifyAsrNone()
    }

    /**
     * Notify glasses that ASR recognition had an error.
     */
    fun notifyAsrError(): ValueUtil.CxrStatus? {
        Log.d(TAG, "Notifying glasses: ASR error")
        return cxrApi?.notifyAsrError()
    }

    /**
     * Notify glasses that ASR recognition has ended.
     */
    fun notifyAsrEnd(): ValueUtil.CxrStatus? {
        Log.d(TAG, "Notifying glasses: ASR end")
        return cxrApi?.notifyAsrEnd()
    }

    /**
     * Send exit event to dismiss the AI scene on glasses.
     */
    fun sendExitEvent(): ValueUtil.CxrStatus? {
        Log.d(TAG, "Sending exit event to glasses AI scene")
        hudForegroundRecovery.scheduleForAiExit(SystemClock.elapsedRealtime())
        val status = cxrApi?.sendExitEvent()
        if (status != ValueUtil.CxrStatus.REQUEST_SUCCEED) {
            hudForegroundRecovery.reset()
        } else {
            mainHandler.removeCallbacks(reopenHudRunnable)
            mainHandler.postDelayed(reopenHudRunnable, HUD_RECOVERY_FALLBACK_DELAY_MS)
        }
        return status
    }

    /**
     * Send TTS content to the glasses AI scene (for displaying AI response text).
     */
    fun sendTtsContent(content: String): ValueUtil.CxrStatus? {
        Log.d(TAG, "Sending TTS content to glasses (${content.length} chars)")
        return cxrApi?.sendTtsContent(content)
    }

    /**
     * Notify glasses that TTS audio has finished.
     */
    fun notifyTtsAudioFinished(): ValueUtil.CxrStatus? {
        Log.d(TAG, "Notifying glasses: TTS finished")
        return cxrApi?.notifyTtsAudioFinished()
    }

    // --- Screen off timeout & wake ---

    /**
     * Configure the glasses idle screen-off timeout via CXR-M SDK.
     * The hardware turns off the display after [seconds] of inactivity
     * and wakes on user interaction.
     */
    fun setScreenOffTimeout(seconds: Long): ValueUtil.CxrStatus? {
        Log.d(TAG, "Setting screen off timeout to ${seconds}s")
        return cxrApi?.setScreenOffTimeout(seconds)
    }

    /**
     * Wake the glasses display from standby by setting brightness and
     * resetting the screen-off timeout via CXR-M SDK.
     *
     * The Rokid micro-LED display is controlled from the phone side — Android
     * PowerManager on the glasses does NOT control it. This method uses
     * setGlassBrightness() to turn the display on and setScreenOffTimeout()
     * to reset the idle timer so it stays on for another 30 seconds.
     *
     * Safe to call repeatedly — setting brightness when already at that
     * level is effectively a no-op.
     */
    fun wakeGlassesScreen(): Boolean {
        if (!isInitialized || !isBluetoothConnectedState) {
            Log.d(TAG, "Cannot wake glasses screen: init=$isInitialized, bt=$isBluetoothConnectedState")
            return false
        }
        return try {
            cxrApi?.setGlassBrightness(lastKnownBrightness)
            cxrApi?.setScreenOffTimeout(30)
            Log.i(TAG, "Wake glasses screen: brightness=$lastKnownBrightness, timeout reset to 30s")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake glasses screen", e)
            false
        }
    }

    // --- Camera methods (for AI photo capture via glasses camera) ---

    private val photoResultCallback = object : PhotoResultCallback {
        override fun onPhotoResult(status: ValueUtil.CxrStatus?, photo: ByteArray?) {
            Log.d(TAG, "Photo result: status=$status, bytes=${photo?.size}")
            onPhotoResult?.invoke(status, photo)
        }
    }

    fun openGlassCamera(width: Int = 1280, height: Int = 720, quality: Int = 75): ValueUtil.CxrStatus? {
        Log.d(TAG, "Opening glass camera: ${width}x${height} quality=$quality")
        return cxrApi?.openGlassCamera(width, height, quality)
    }

    fun takeGlassPhoto(width: Int = 1280, height: Int = 720, quality: Int = 75): ValueUtil.CxrStatus? {
        Log.d(TAG, "Taking glass photo: ${width}x${height} quality=$quality")
        return cxrApi?.takeGlassPhoto(width, height, quality, photoResultCallback)
    }

    fun takeGlassPhotoGlobal(width: Int = 1280, height: Int = 720, quality: Int = 75): ValueUtil.CxrStatus? {
        Log.d(TAG, "Taking glass photo (global): ${width}x${height} quality=$quality")
        return cxrApi?.takeGlassPhotoGlobal(width, height, quality, photoResultCallback)
    }

    /**
     * Cleanup SDK resources
     */
    fun cleanup() {
        if (!isInitialized) return

        try {
            connectionAttemptGate.cancel()
            pendingConnectAttemptId = null
            snAutoRetryAttemptId = null
            mainHandler.removeCallbacks(reopenHudRunnable)
            hudForegroundRecovery.reset()
            deinitWifiP2P()
            cxrApi?.clearCommunicationDevice()
            cxrApi = null
            appContext = null
            isInitialized = false
            isBluetoothConnectedState = false
            isWifiP2PConnectedState = false
            Log.d(TAG, "Rokid SDK cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up Rokid SDK", e)
        }
    }

    private const val HOTSPOT_CONNECT_TIMEOUT_MS = 20_000
}
