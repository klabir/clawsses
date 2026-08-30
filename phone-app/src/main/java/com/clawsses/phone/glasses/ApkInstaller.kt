package com.clawsses.phone.glasses

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.clawsses.phone.service.GlassesConnectionService
import com.clawsses.phone.service.WakeLockReason
import com.clawsses.phone.BuildConfig
import dadb.Dadb
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay

/**
 * Handles APK installation on Rokid glasses.
 *
 * Production installs prefer the official Hi Rokid bridge and automatically fall back to the
 * connected CXR-M transport. The debug-only ADB entry points remain available for development.
 *
 * The ADB method is more reliable for development and doesn't require the full SDK setup.
 */
class ApkInstaller(
    private val context: Context,
    private val glassesManager: GlassesConnectionManager,
) {

    companion object {
        private const val TAG = "ApkInstaller"
        private const val GLASSES_APP_ASSET = "glasses-app-release.apk"
        private const val DEFAULT_ADB_PORT = 5555
        private const val ADB_OPERATION_TIMEOUT_MS = 60_000L
        private const val PEER_HANDSHAKE_TIMEOUT_MS = 60_000L
        private const val KEY_PENDING_PEER_BUILD = "pending_peer_build"
    }

    /**
     * Installation method
     */
    enum class InstallMethod {
        SDK,    // Use Rokid CXR-M SDK (WiFi P2P)
        ADB     // Use ADB over WiFi
    }

    /**
     * Installation state machineIt
     */
    sealed class InstallState {
        object Idle : InstallState()
        object CheckingConnection : InstallState()
        object InitializingWifiP2P : InstallState()
        object InitializingWifiHotspot : InstallState()
        object AwaitingHiRokidAuthorization : InstallState()
        data class ConnectingHiRokid(val message: String) : InstallState()
        data class AwaitingPeerOwnership(val expectedBuild: Int) : InstallState()
        data class VerifyingPeer(val expectedBuild: Int) : InstallState()
        data class InstalledPendingVerification(
            val expectedBuild: Int,
            val message: String,
        ) : InstallState()
        object PreparingApk : InstallState()
        data class Uploading(val message: String = "Uploading APK...", val progress: Int = -1) : InstallState()
        data class Installing(val message: String = "Installing...") : InstallState()
        data class Success(val message: String = "Installation complete!") : InstallState()
        data class Error(val message: String, val canRetry: Boolean = true) : InstallState()
    }

    private val installerPreferences = context.getSharedPreferences(
        "clawsses_installer_state",
        Context.MODE_PRIVATE,
    )
    private val transactionStore = InstallerTransactionStore(context)
    private val restoredPendingBuild = installerPreferences
        .getInt(KEY_PENDING_PEER_BUILD, 0)
        .takeIf { it > 0 }
    private val _installState = MutableStateFlow<InstallState>(
        restoredPendingBuild?.let(::pendingVerificationState)
            ?: transactionStore.active()?.let(::interruptedInstallState)
            ?: InstallState.Idle,
    )
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var installJob: Job? = null
    private val hiRokidInstaller = HiRokidInstaller(context, glassesManager)

    init {
        scope.launch {
            glassesManager.peerBuild.filterNotNull().collect { peerBuild ->
                val expectedBuild = pendingPeerBuild() ?: return@collect
                val gate = PeerInstallVerification(expectedBuild)
                if (gate.observe(peerBuild) == PeerVerificationResult.VERIFIED) {
                    markPeerVerified(expectedBuild)
                }
            }
        }
    }

    /** Activity-owned launcher; the authorization token is returned directly and never persisted. */
    var launchHiRokidAuthorization: ((Intent) -> Unit)? = null

    /** One production entry point. Transport choice is deterministic and tested. */
    fun installOrUpdate() {
        if (!canStartInstall()) return
        val hiRokidAvailable = hiRokidInstaller.isAvailable()
        val authorizationLauncherAvailable = launchHiRokidAuthorization != null
        val cxrReady = RokidSdkManager.isReady()
        val cxrConnected = RokidSdkManager.isConnected()
        when (
            InstallerTransportPolicy.select(
                InstallerAvailability(
                    hiRokidAvailable = hiRokidAvailable,
                    authorizationLauncherAvailable = authorizationLauncherAvailable,
                    cxrReady = cxrReady,
                    cxrConnected = cxrConnected,
                ),
            )
        ) {
            InstallerRoute.HI_ROKID -> installViaHiRokid()
            InstallerRoute.CXR_M -> installViaSdk()
            null -> {
                transactionStore.clear()
                _installState.value = InstallState.Error(
                    unavailableRouteMessage(
                        hiRokidAvailable = hiRokidAvailable,
                        authorizationLauncherAvailable = authorizationLauncherAvailable,
                        cxrReady = cxrReady,
                    ),
                    canRetry = true,
                )
            }
        }
    }

    // ADB connection settings
    private var adbHost: String = ""
    private var adbPort: Int = DEFAULT_ADB_PORT

    /**
     * Configure ADB connection for installation.
     * Call this before using installViaAdb().
     */
    fun configureAdb(host: String, port: Int = DEFAULT_ADB_PORT) {
        this.adbHost = host
        this.adbPort = port
        Log.d(TAG, "ADB configured: $host:$port")
    }

    /**
     * Install the glasses app using ADB over WiFi.
     * This is more reliable for development than the SDK method.
     *
     * Prerequisites on glasses:
     * 1. Enable Developer Options (tap Build Number 7 times)
     * 2. Enable USB debugging
     * 3. Connect glasses to same WiFi network as phone
     * 4. Find glasses IP: Settings > About > IP address
     */
    fun installViaAdb(host: String? = null, port: Int? = null) {
        val targetHost = host ?: adbHost
        val targetPort = port ?: adbPort

        if (targetHost.isEmpty()) {
            _installState.value = InstallState.Error(
                "ADB host not configured. Go to Settings and enter the glasses IP address.",
                canRetry = false
            )
            return
        }

        if (!canStartInstall()) return

        Log.i(TAG, "Starting ADB installation to $targetHost:$targetPort")
        _installState.value = InstallState.CheckingConnection

        installJob = scope.launch {
            try {
                withTimeout(ADB_OPERATION_TIMEOUT_MS) {
                    doAdbInstall(targetHost, targetPort)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Installation timed out after ${ADB_OPERATION_TIMEOUT_MS}ms")
                _installState.value = InstallState.Error("Installation timed out. Check glasses connection.")
            } catch (e: CancellationException) {
                Log.d(TAG, "Installation cancelled")
                _installState.value = InstallState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Installation failed", e)
                _installState.value = InstallState.Error(formatError(e))
            }
        }
    }

    private suspend fun doAdbInstall(host: String, port: Int) = withContext(Dispatchers.IO) {
        // Step 1: Test connection
        Log.d(TAG, "Testing ADB connection to $host:$port...")
        _installState.value = InstallState.CheckingConnection

        val dadb = try {
            Dadb.create(host, port)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ADB", e)
            throw Exception("Cannot connect to glasses at $host:$port. " +
                "Ensure ADB debugging is enabled and glasses are on the same network.")
        }

        dadb.use { adb ->
            // Verify connection works
            val testResult = adb.shell("echo connected")
            if (testResult.exitCode != 0) {
                throw Exception("ADB connection test failed. Check if glasses accepted the connection.")
            }
            Log.d(TAG, "ADB connection verified")

            // Step 2: Prepare APK
            _installState.value = InstallState.PreparingApk
            val apkFile = extractApkFromAssets()
                ?: throw Exception("No APK found. Ensure glasses-app-release.apk is bundled.")

            Log.d(TAG, "APK prepared: ${apkFile.absolutePath} (${apkFile.length() / 1024} KB)")

            // Step 3: Install APK
            _installState.value = InstallState.Uploading("Uploading ${apkFile.length() / 1024} KB...")

            try {
                Log.d(TAG, "Installing APK via ADB...")
                _installState.value = InstallState.Installing("Installing on glasses...")
                adb.install(apkFile, "-r") // -r = replace existing

                Log.i(TAG, "APK installation successful!")
                _installState.value = InstallState.Success("Glasses app installed successfully!")

            } catch (e: Exception) {
                Log.e(TAG, "APK installation failed", e)
                throw Exception("Installation failed: ${e.message}")
            } finally {
                // Cleanup temp file
                cleanupTempApk()
            }
        }
    }

    /**
     * Install the bundled glasses app APK using SDK method (WiFi P2P).
     * Requires: SDK initialized, Bluetooth connected to glasses.
     *
     * The SDK will automatically establish WiFi P2P for the transfer.
     */
    fun installViaSdk() {
        if (!canStartInstall()) return

        // Check SDK initialization
        if (!RokidSdkManager.isReady()) {
            Log.e(TAG, "Rokid SDK not initialized")
            _installState.value = InstallState.Error(
                "Rokid SDK not initialized. Check if credentials are configured in local.properties.",
                canRetry = false
            )
            return
        }

        // Check Bluetooth connection
        if (!RokidSdkManager.isConnected()) {
            Log.e(TAG, "Not connected to glasses via Bluetooth")
            _installState.value = InstallState.Error(
                "Not connected to glasses. Connect to glasses via Bluetooth first.",
                canRetry = true
            )
            return
        }

        transactionStore.start(
            InstallerRoute.CXR_M,
            InstallerPhase.PREPARING_APK,
            BuildConfig.VERSION_CODE,
        )
        Log.i(TAG, "Starting SDK installation via the automatic CXR-M transport policy")
        _installState.value = InstallState.PreparingApk
        GlassesConnectionService.holdWakeLock(
            context,
            WakeLockReason.APK_TRANSFER,
            ApkInstallerTimeoutPolicy.TOTAL_OPERATION_MS,
        )

        installJob = scope.launch {
            try {
                withTimeout(ApkInstallerTimeoutPolicy.TOTAL_OPERATION_MS) {
                    doSdkInstall()
                }
                persistPendingPeerBuild(BuildConfig.VERSION_CODE)
                transactionStore.advance(InstallerPhase.VERIFYING)
                verifyPendingPeerBuild()
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "SDK installation phase timed out")
                RokidSdkManager.stopUploadApk()
                _installState.value = InstallState.Error("Installation timed out. Check glasses connection.")
                transactionStore.clear()
            } catch (e: CancellationException) {
                Log.d(TAG, "SDK installation cancelled")
                _installState.value = InstallState.Idle
                transactionStore.clear()
            } catch (e: Exception) {
                Log.e(TAG, "SDK installation failed", e)
                RokidSdkManager.stopUploadApk()
                _installState.value = InstallState.Error(formatError(e))
                transactionStore.clear()
            } finally {
                GlassesConnectionService.releaseWakeLock(context, WakeLockReason.APK_TRANSFER)
                RokidSdkManager.deinitWifiHotspot()
                RokidSdkManager.onApkUploadSucceed = null
                RokidSdkManager.onApkUploadFailed = null
                RokidSdkManager.onApkInstallSucceed = null
                RokidSdkManager.onApkInstallFailed = null
                cleanupTempApk()
            }
        }
    }

    fun installViaHiRokid() {
        if (!canStartInstall()) return
        if (!hiRokidInstaller.isAvailable()) {
            _installState.value = InstallState.Error(
                "Enable or install the official Hi Rokid app, then try again.",
                canRetry = true,
            )
            return
        }
        val launcher = launchHiRokidAuthorization
        if (launcher == null) {
            _installState.value = InstallState.Error(
                "Open Clawsses on the phone to authorize the Hi Rokid installer.",
                canRetry = true,
            )
            return
        }
        transactionStore.start(
            InstallerRoute.HI_ROKID,
            InstallerPhase.AWAITING_AUTHORIZATION,
            BuildConfig.VERSION_CODE,
        )
        _installState.value = InstallState.AwaitingHiRokidAuthorization
        runCatching { launcher(hiRokidInstaller.authorizationIntent()) }
            .onFailure { error ->
                _installState.value = InstallState.Error(
                    "Could not open Hi Rokid authorization: ${error.message ?: error.javaClass.simpleName}",
                    canRetry = true,
                )
                transactionStore.clear()
            }
    }

    fun handleHiRokidAuthorization(resultCode: Int, data: Intent?) {
        if (_installState.value !is InstallState.AwaitingHiRokidAuthorization) return
        val token = hiRokidInstaller.parseAuthorizationResult(resultCode, data)
        if (token == null) {
            transactionStore.clear()
            _installState.value = pendingPeerBuild()?.let(::pendingVerificationState)
                ?: InstallState.Error(
                    "Hi Rokid authorization was cancelled or denied.",
                    canRetry = true,
                )
            return
        }

        clearPendingPeerBuild()

        GlassesConnectionService.holdWakeLock(
            context,
            WakeLockReason.APK_TRANSFER,
            ApkInstallerTimeoutPolicy.TOTAL_OPERATION_MS,
        )
        installJob = scope.launch {
            try {
                transactionStore.advance(InstallerPhase.PREPARING_APK)
                _installState.value = InstallState.PreparingApk
                val apkFile = withContext(Dispatchers.IO) {
                    extractApkFromAssets()
                        ?: throw IllegalStateException("Bundled glasses APK is missing.")
                }
                val receipt = hiRokidInstaller.install(apkFile, token) { message ->
                    transactionStore.advance(InstallerPhase.CONNECTING)
                    _installState.value = InstallState.ConnectingHiRokid(message)
                }
                check(receipt.installed && receipt.opened)
                persistPendingPeerBuild(BuildConfig.VERSION_CODE)
                transactionStore.advance(InstallerPhase.VERIFYING)
                _installState.value = InstallState.AwaitingPeerOwnership(BuildConfig.VERSION_CODE)
                verifyPendingPeerBuild()
            } catch (error: TimeoutCancellationException) {
                _installState.value = InstallState.Error(
                    "Hi Rokid did not complete the APK installation within its time limit.",
                    canRetry = true,
                )
                transactionStore.clear()
            } catch (error: CancellationException) {
                _installState.value = pendingPeerBuild()?.let(::pendingVerificationState)
                    ?: InstallState.Idle
                if (pendingPeerBuild() == null) transactionStore.clear()
            } catch (error: Exception) {
                Log.e(TAG, "Hi Rokid installation failed", error)
                _installState.value = InstallState.Error(formatError(error), canRetry = true)
                transactionStore.clear()
            } finally {
                hiRokidInstaller.cancel()
                GlassesConnectionService.releaseWakeLock(context, WakeLockReason.APK_TRANSFER)
                cleanupTempApk()
            }
        }
    }

    fun retryPendingVerification() {
        val expectedBuild = pendingPeerBuild()
        if (expectedBuild == null) {
            _installState.value = InstallState.Idle
            return
        }
        if (installJob?.isActive == true) return
        installJob = scope.launch { verifyPendingPeerBuild() }
    }

    private suspend fun verifyPendingPeerBuild() {
        val expectedBuild = pendingPeerBuild() ?: return
        _installState.value = InstallState.VerifyingPeer(expectedBuild)
        withContext(Dispatchers.Main.immediate) {
            glassesManager.retryReconnectNow()
        }
        val verified = withTimeoutOrNull(PEER_HANDSHAKE_TIMEOUT_MS) {
            glassesManager.peerBuild
                .filter { it == expectedBuild }
                .first()
        } != null
        if (verified) {
            markPeerVerified(expectedBuild)
        } else {
            _installState.value = pendingVerificationState(expectedBuild)
        }
    }

    private fun persistPendingPeerBuild(expectedBuild: Int) {
        installerPreferences.edit().putInt(KEY_PENDING_PEER_BUILD, expectedBuild).apply()
    }

    private fun pendingPeerBuild(): Int? = installerPreferences
        .getInt(KEY_PENDING_PEER_BUILD, 0)
        .takeIf { it > 0 }

    private fun clearPendingPeerBuild() {
        installerPreferences.edit().remove(KEY_PENDING_PEER_BUILD).apply()
    }

    private fun markPeerVerified(expectedBuild: Int) {
        if (pendingPeerBuild() != expectedBuild) return
        clearPendingPeerBuild()
        val route = transactionStore.active()?.route?.label ?: "installer"
        transactionStore.clear()
        _installState.value = InstallState.Success(
            "Glasses Build $expectedBuild installed and verified via $route.",
        )
    }

    private suspend fun doSdkInstall() = withContext(Dispatchers.IO) {
        transactionStore.advance(InstallerPhase.PREPARING_APK)
        // Step 1: Prepare APK
        val apkFile = extractApkFromAssets()
            ?: throw Exception("No APK found. Ensure glasses-app-release.apk is bundled.")

        Log.d(TAG, "APK prepared: ${apkFile.absolutePath} (${apkFile.length() / 1024} KB)")

        // Step 2: Set up callbacks for progress tracking
        val installCompletion = CompletableDeferred<Unit>()

        RokidSdkManager.onApkUploadSucceed = {
            Log.d(TAG, "SDK: APK upload succeeded")
            _installState.value = InstallState.Installing("Installing on glasses...")
            transactionStore.advance(InstallerPhase.INSTALLING)
        }

        RokidSdkManager.onApkUploadFailed = {
            Log.e(TAG, "SDK: APK upload failed")
            installCompletion.completeExceptionally(
                IllegalStateException("APK upload failed. Check glasses hotspot connection."),
            )
        }

        RokidSdkManager.onApkInstallSucceed = {
            Log.d(TAG, "SDK: APK installation succeeded")
            installCompletion.complete(Unit)
        }

        RokidSdkManager.onApkInstallFailed = {
            Log.e(TAG, "SDK: APK installation failed")
            installCompletion.completeExceptionally(
                IllegalStateException("APK installation failed on glasses."),
            )
        }

        // Step 3: Check WiFi P2P permission (Android 13+ requires NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "NEARBY_WIFI_DEVICES permission: ${if (hasPermission) "GRANTED" else "DENIED"}")
            if (!hasPermission) {
                throw Exception(
                    "Missing 'Nearby devices' permission.\n\n" +
                    "Go to Android Settings > Apps > Clawsses > Permissions > Nearby devices and enable it."
                )
            }
        }

        // Step 4: Prefer the official glasses-hotspot transport. Android's public
        // Wi-Fi Direct discovery does not expose the peer reported by current
        // firmware, while the CXR hotspot path is explicit and IP-addressed.
        withContext(Dispatchers.Main) {
            RokidSdkManager.refreshGlassesCapabilities()
        }
        var capabilityWaitMs = 0
        while (capabilityWaitMs < ApkInstallerTimeoutPolicy.CAPABILITY_WAIT_MS) {
            val pending = RokidSdkManager.capabilities.value
            if (pending.glassInfoAvailable || pending.sdkVersionCheckPassed != null) break
            delay(100)
            capabilityWaitMs += 100
        }
        val capabilitySnapshot = RokidSdkManager.capabilities.value
        val transportOrder = GlassesCapabilityPolicy.installTransportOrder(capabilitySnapshot)
        Log.i(
            TAG,
            "Installer policy: firmware=" +
                "${GlassesCapabilityPolicy.firmwareSupport(capabilitySnapshot)}, " +
                "transports=${transportOrder.joinToString()}",
        )
        var uploadIp: String? = null
        if (InstallerTransport.GLASSES_HOTSPOT in transportOrder) {
            _installState.value = InstallState.InitializingWifiHotspot
            withContext(Dispatchers.Main) { RokidSdkManager.deinitWifiHotspot() }
            delay(ApkInstallerTimeoutPolicy.HOTSPOT_RESET_DELAY_MS)
            uploadIp = try {
                RokidSdkManager.awaitWifiHotspotConnection(
                    ApkInstallerTimeoutPolicy.HOTSPOT_CONNECTION_MS,
                ).ipAddress
            } catch (error: TimeoutCancellationException) {
                Log.w(TAG, "Glasses hotspot attempt timed out")
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Glasses hotspot attempt failed: ${error.message}")
                null
            }
            if (uploadIp == null) {
                withContext(Dispatchers.Main) { RokidSdkManager.deinitWifiHotspot() }
            }
        }

        // Fall back to Wi-Fi Direct for older firmware or devices where the
        // hotspot transport is unavailable. Rokid's controller is Handler/Looper
        // based, so lifecycle calls must run on the main thread.
        if (uploadIp == null &&
            InstallerTransport.VENDOR_P2P in transportOrder &&
            !RokidSdkManager.isWifiP2PConnected()
        ) {
            _installState.value = InstallState.InitializingWifiP2P
            var connected = false
            for (attempt in 1..ApkInstallerTimeoutPolicy.P2P_ATTEMPTS) {
                Log.i(
                    TAG,
                    "Initializing WiFi P2P for APK transfer " +
                        "(attempt $attempt/${ApkInstallerTimeoutPolicy.P2P_ATTEMPTS})...",
                )
                val started = withContext(Dispatchers.Main) {
                    RokidSdkManager.initWifiP2P()
                }
                if (!started) {
                    Log.w(TAG, "WiFi P2P initialization request was rejected")
                } else {
                    var waitTime = 0
                    while (!RokidSdkManager.isWifiP2PConnected() &&
                        waitTime < ApkInstallerTimeoutPolicy.P2P_ATTEMPT_MS
                    ) {
                        delay(500)
                        waitTime += 500
                        if (waitTime % 5_000 == 0) {
                            Log.i(TAG, "Still waiting for WiFi P2P... (${waitTime / 1000}s)")
                        }
                    }
                    connected = RokidSdkManager.isWifiP2PConnected()
                }
                if (connected) break

                withContext(Dispatchers.Main) {
                    RokidSdkManager.deinitWifiP2P()
                }
                if (attempt < ApkInstallerTimeoutPolicy.P2P_ATTEMPTS) {
                    delay(ApkInstallerTimeoutPolicy.P2P_RETRY_DELAY_MS)
                }
            }

            if (!connected) {
                throw Exception(
                    "Neither the glasses hotspot nor WiFi P2P could be established.\n\n" +
                    "Keep the glasses unfolded, awake, and in pairing mode, then try again."
                )
            }
        }

        // Step 4: Start APK upload
        Log.d(TAG, "Starting APK upload via SDK...")
        val transportName = if (uploadIp == null) "WiFi P2P" else "glasses hotspot"
        _installState.value = InstallState.Uploading(
            "Uploading ${apkFile.length() / 1024} KB via $transportName..."
        )
        transactionStore.advance(InstallerPhase.UPLOADING)

        val started = withContext(Dispatchers.Main) {
            uploadIp?.let { RokidSdkManager.startUploadApk(apkFile.absolutePath, it) }
                ?: RokidSdkManager.startUploadApk(apkFile.absolutePath)
        }
        if (!started) {
            throw Exception("Failed to start APK upload. Check SDK connection.")
        }

        // Step 5: Await the SDK callback without polling cross-thread flags.
        withTimeout(ApkInstallerTimeoutPolicy.INSTALL_COMPLETION_MS) {
            installCompletion.await()
        }

        Log.i(TAG, "SDK APK installation successful!")
        // Disconnect WiFi P2P to save battery — Bluetooth remains for communication.
        // Must switch to Main thread since SDK methods require it.
        withContext(Dispatchers.Main) {
            disconnectWifiP2PAfterInstall()
        }
    }

    /**
     * Disconnect WiFi P2P after a successful SDK install to save battery.
     * Only disconnects if Bluetooth is still active (so communication isn't lost).
     * Must be called on the Main thread (SDK requirement).
     */
    private fun disconnectWifiP2PAfterInstall() {
        Log.i(TAG, "Post-install: checking Bluetooth before disconnecting WiFi P2P...")

        if (!RokidSdkManager.isConnected()) {
            Log.w(TAG, "Post-install: Bluetooth not connected — keeping WiFi P2P active as fallback")
            return
        }
        Log.i(TAG, "Post-install: Bluetooth confirmed active")

        if (!RokidSdkManager.isWifiP2PConnected()) {
            Log.i(TAG, "Post-install: WiFi P2P already disconnected — nothing to do")
            return
        }

        Log.i(TAG, "Post-install: disconnecting WiFi P2P to save battery...")
        RokidSdkManager.deinitWifiP2P()
        Log.i(TAG, "Post-install: WiFi P2P disconnected successfully")
    }

    /**
     * Legacy method for backwards compatibility.
     * Tries SDK first, suggests ADB on failure.
     */
    fun installGlassesApp() {
        if (!canStartInstall()) return

        // Try to determine best method
        if (adbHost.isNotEmpty()) {
            // ADB is configured, use it
            installViaAdb()
        } else if (RokidSdkManager.isReady() && RokidSdkManager.isConnected()) {
            // SDK available, try it
            installViaSdk()
        } else {
            // Nothing configured - show helpful error
            _installState.value = InstallState.Error(
                "Configure installation method:\n\n" +
                "ADB Method (recommended for development):\n" +
                "1. Enable Developer Options on glasses\n" +
                "2. Enable USB/ADB debugging\n" +
                "3. Connect glasses to WiFi\n" +
                "4. Enter glasses IP address below\n\n" +
                "SDK Method:\n" +
                "Requires Rokid SDK credentials and Bluetooth pairing.",
                canRetry = false
            )
        }
    }

    /**
     * Test ADB connection to glasses without installing.
     */
    fun testAdbConnection(host: String, port: Int = DEFAULT_ADB_PORT, onResult: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "Testing ADB connection to $host:$port")
                    Dadb.create(host, port).use { adb ->
                        val result = adb.shell("getprop ro.product.model")
                        if (result.exitCode == 0) {
                            val model = result.output.trim()
                            Log.d(TAG, "ADB connection successful: $model")
                            onResult(true, "Connected to: $model")
                        } else {
                            onResult(false, "Connection failed: ${result.output}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ADB connection test failed", e)
                onResult(false, formatError(e))
            }
        }
    }

    /**
     * Cancel the current installation.
     */
    fun cancelInstallation() {
        Log.d(TAG, "Cancelling installation")
        RokidSdkManager.stopUploadApk()
        hiRokidInstaller.cancel()
        installJob?.cancel()
        installJob = null
        if (pendingPeerBuild() == null) transactionStore.clear()
        _installState.value = pendingPeerBuild()?.let(::pendingVerificationState)
            ?: InstallState.Idle
    }

    /**
     * Reset state to idle.
     */
    fun resetState() {
        _installState.value = pendingPeerBuild()?.let(::pendingVerificationState) ?: InstallState.Idle
        _lastError.value = null
    }

    private fun canStartInstall(): Boolean {
        val currentState = _installState.value
        if (currentState !is InstallState.Idle &&
            currentState !is InstallState.Error &&
            currentState !is InstallState.Success &&
            currentState !is InstallState.InstalledPendingVerification) {
            Log.w(TAG, "Installation already in progress: $currentState")
            return false
        }
        return true
    }

    private fun extractApkFromAssets(): File? {
        return try {
            val cacheDir = context.cacheDir
            val apkFile = File(cacheDir, "glasses-app.apk")

            // Check if we have a bundled APK in assets
            val assetManager = context.assets
            val assetList = assetManager.list("") ?: emptyArray()

            if (GLASSES_APP_ASSET in assetList) {
                Log.d(TAG, "Extracting bundled APK from assets")
                assetManager.open(GLASSES_APP_ASSET).use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }
                apkFile
            } else {
                // Check debug APK location
                val debugApk = File(cacheDir, "glasses-app-debug.apk")
                if (debugApk.exists()) {
                    Log.d(TAG, "Using debug APK from cache: ${debugApk.absolutePath}")
                    return debugApk
                }

                // Check external files directory
                val externalApk = File(context.getExternalFilesDir(null), "glasses-app.apk")
                if (externalApk.exists()) {
                    Log.d(TAG, "Using APK from external files: ${externalApk.absolutePath}")
                    externalApk
                } else {
                    Log.e(TAG, "No glasses-app APK found. Checked: assets/$GLASSES_APP_ASSET, $debugApk, $externalApk")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting APK from assets", e)
            null
        }
    }

    private fun cleanupTempApk() {
        try {
            File(context.cacheDir, "glasses-app.apk").delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up cached APK", e)
        }
    }

    private fun formatError(e: Exception): String {
        val message = e.message ?: "Unknown error"
        return when {
            message.contains("Connection refused") ->
                "Connection refused. Ensure:\n" +
                "1. ADB debugging is enabled on glasses\n" +
                "2. Glasses are on the same WiFi network\n" +
                "3. IP address is correct"
            message.contains("timeout", ignoreCase = true) ->
                "Connection timed out. Check:\n" +
                "1. Glasses IP address\n" +
                "2. WiFi connectivity\n" +
                "3. Firewall settings"
            message.contains("INSTALL_FAILED") ->
                "Installation failed: $message\n" +
                "Try uninstalling the existing app first."
            message.contains("No route to host") ->
                "Cannot reach glasses. Ensure they're on the same network."
            else -> message
        }
    }

    fun cleanup() {
        RokidSdkManager.stopUploadApk()
        hiRokidInstaller.cancel()
        installJob?.cancel()
        scope.cancel()
        cleanupTempApk()
    }

    private fun pendingVerificationState(expectedBuild: Int) =
        InstallState.InstalledPendingVerification(
            expectedBuild = expectedBuild,
            message = "The HUD was installed. Reconnect the glasses to verify Build $expectedBuild.",
        )

    private fun interruptedInstallState(transaction: InstallerTransaction) = InstallState.Error(
        "The previous ${transaction.route.label} installation was interrupted during " +
            "${transaction.phase.displayName()}. Start the update again; no authorization token " +
            "or hotspot credential was stored.",
        canRetry = true,
    )

    private fun InstallerPhase.displayName(): String = name
        .lowercase()
        .replace('_', ' ')

    private fun unavailableRouteMessage(
        hiRokidAvailable: Boolean,
        authorizationLauncherAvailable: Boolean,
        cxrReady: Boolean,
    ): String = when {
        !hiRokidAvailable && cxrReady ->
            "Hi Rokid is disabled or unavailable, and CXR-M is disconnected. Press the glasses " +
                "button 3× until the blue LED blinks, reconnect Clawsses, then try again."

        hiRokidAvailable && !authorizationLauncherAvailable ->
            "Open Clawsses in the foreground so Android can request Hi Rokid authorization."

        !cxrReady ->
            "The private Rokid installer is unavailable in this build. Use an authorized paired " +
                "hardware build, or enable Hi Rokid and try again."

        else ->
            "No installer route is ready. Enable Hi Rokid or reconnect the glasses through CXR-M."
    }

}
