package com.clawsses.phone.glasses

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.util.Log
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** Official CXR-L bridge used when the CXR-M hotspot/P2P installer is unavailable. */
internal class HiRokidInstaller(
    context: Context,
    private val glassesManager: GlassesConnectionManager,
) {
    companion object {
        private const val TAG = "HiRokidInstaller"
        private const val HI_ROKID_PACKAGE = "com.rokid.sprite.global.aiapp"
        private const val AUTH_ACTIVITY =
            "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity"
        private const val MEDIA_SERVICE_ACTION =
            "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        private const val AUTH_TOKEN_EXTRA = "auth_token"
        private const val AUTH_PACKAGE_EXTRA = "auth_package"
        private const val HUD_ACTIVITY = "com.clawsses.glasses.HudActivity"
        private const val HANDOFF_DELAY_MS = 900L
        private const val INSTALL_TIMEOUT_MS = 120_000L
    }

    private val appContext = context.applicationContext
    private var link: CXRLink? = null
    private var serviceConnection: ServiceConnection? = null
    private var bound = false
    private var companionUiLaunched = false

    fun isAvailable(): Boolean = runCatching {
        val info = appContext.packageManager.getApplicationInfo(HI_ROKID_PACKAGE, 0)
        info.enabled && (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
    }.getOrDefault(false)

    fun authorizationIntent(): Intent = Intent().setClassName(HI_ROKID_PACKAGE, AUTH_ACTIVITY)

    fun parseAuthorizationResult(resultCode: Int, data: Intent?): String? {
        return runCatching {
            when (val result = AuthorizationHelper.parseAuthorizationResult(resultCode, data)) {
                is AuthResult.AuthSuccess -> result.token.takeIf(String::isNotBlank)
                is AuthResult.AuthCancel,
                is AuthResult.AuthFail -> null
                else -> null
            }
        }.getOrNull()
    }

    private fun bringCompanionToForeground() {
        if (companionUiLaunched) return
        val launchIntent = appContext.packageManager
            .getLaunchIntentForPackage(HI_ROKID_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        companionUiLaunched = runCatching {
            appContext.startActivity(launchIntent)
            Log.i(TAG, "Opened Hi Rokid for the CXR-L Bluetooth handoff")
            true
        }.getOrDefault(false)
    }

    suspend fun install(apkFile: File, token: String, onStatus: (String) -> Unit) {
        require(apkFile.isFile && apkFile.length() > 0L) { "Bundled glasses APK is missing." }
        require(token.isNotBlank()) { "Hi Rokid authorization token is empty." }

        val packageName = appContext.packageManager
            .getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?.packageName
            ?.takeIf(String::isNotBlank)
            ?: error("Could not read the bundled glasses package name.")
        val completion = CompletableDeferred<Boolean>()
        var gate = HiRokidInstallGate()
        val cxrLink = CXRLink(appContext)
        link = cxrLink

        withContext(Dispatchers.Main.immediate) {
            glassesManager.disconnectForExternalHandoff()
        }
        delay(HANDOFF_DELAY_MS)

        fun maybeStartUpload() {
            synchronized(completion) {
                val claimed = gate.claimUpload() ?: return
                gate = claimed
                onStatus("Uploading through Hi Rokid...")
                Log.i(TAG, "CXR-L CUSTOMAPP ready; starting bundled glasses APK install")
                cxrLink.appUploadAndInstall(apkFile.absolutePath, object : IGlassAppCbk {
                    override fun onInstallAppResult(success: Boolean) {
                        if (!success) {
                            completion.complete(false)
                            return
                        }
                        onStatus("Installation complete; launching the glasses app...")
                        Log.i(TAG, "CXR-L install complete; launching the bundled glasses app")
                        cxrLink.appStart(HUD_ACTIVITY, this)
                    }

                    override fun onUnInstallAppResult(success: Boolean) = Unit
                    override fun onOpenAppResult(success: Boolean) {
                        completion.complete(success)
                    }
                    override fun onStopAppResult(success: Boolean) = Unit
                    override fun onGlassAppResume(resumed: Boolean) = Unit
                    override fun onQueryAppResult(installed: Boolean) = Unit
                })
            }
        }

        cxrLink.setCXRLinkCbk(object : ICXRLinkCbk {
            override fun onCXRLConnected(connected: Boolean) {
                onStatus(if (connected) "Hi Rokid service connected" else "Hi Rokid service disconnected")
                synchronized(completion) {
                    gate = gate.withLinkConnected(connected)
                    maybeStartUpload()
                }
            }

            override fun onGlassBtConnected(connected: Boolean) {
                onStatus(
                    if (connected) "Glasses Bluetooth connected"
                    else "Hi Rokid is connecting. Wake the glasses and enter blue-blink mode",
                )
                synchronized(completion) {
                    gate = gate.withGlassesBluetoothConnected(connected)
                    maybeStartUpload()
                }
            }

            override fun onGlassDeviceInfo(info: GlassInfo) = Unit
            override fun onGlassWearingStatus(wearing: Boolean) = Unit
            override fun onGlassAiAssistStart() = Unit
            override fun onGlassAiAssistStop() = Unit
            override fun onGlassAiInterrupt(interrupted: Boolean) = Unit
            override fun onGlassLauncherResume() = Unit
        })

        try {
            check(
                cxrLink.configCXRSession(
                    CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, packageName),
                ),
            ) { "Could not configure the Hi Rokid CUSTOMAPP session." }

            onStatus("Connecting through the official Hi Rokid bridge...")
            val connection = findServiceConnection(cxrLink)
            serviceConnection = connection
            val bindIntent = Intent(MEDIA_SERVICE_ACTION)
                .setPackage(HI_ROKID_PACKAGE)
                .putExtra(AUTH_TOKEN_EXTRA, token)
                .putExtra(AUTH_PACKAGE_EXTRA, appContext.packageName)
            bound = withContext(Dispatchers.Main.immediate) {
                appContext.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)
            }
            check(bound) { "Could not bind the official Hi Rokid service." }
            bringCompanionToForeground()

            val installed = withTimeout(INSTALL_TIMEOUT_MS) { completion.await() }
            check(installed) { "Hi Rokid reported that glasses installation failed." }
        } finally {
            cleanupConnection()
            withContext(Dispatchers.Main.immediate) {
                if (RokidSdkManager.hasSavedConnectionInfo()) {
                    glassesManager.retryReconnectNow()
                }
            }
        }
    }

    fun cancel() = cleanupConnection()

    private fun findServiceConnection(cxrLink: CXRLink): ServiceConnection {
        var type: Class<*>? = cxrLink.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull {
                ServiceConnection::class.java.isAssignableFrom(it.type)
            }
            if (field != null) {
                field.isAccessible = true
                return field.get(cxrLink) as? ServiceConnection
                    ?: error("Hi Rokid CXR-L ServiceConnection is unavailable.")
            }
            type = type.superclass
        }
        error("Hi Rokid CXR-L ServiceConnection was not found.")
    }

    private fun cleanupConnection() {
        val currentLink = link
        val connection = serviceConnection
        runCatching { currentLink?.disconnect() }
        if (bound && connection != null) {
            runCatching { appContext.unbindService(connection) }
        }
        link = null
        serviceConnection = null
        bound = false
        companionUiLaunched = false
    }
}
