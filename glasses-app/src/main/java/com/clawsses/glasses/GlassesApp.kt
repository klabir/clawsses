package com.clawsses.glasses

import android.app.Application
import android.os.Build
import android.util.Log
import com.clawsses.glasses.service.PhoneConnectionService

class GlassesApp : Application() {

    lateinit var phoneConnection: PhoneConnectionService
        private set

    companion object {
        const val TAG = "GlassesHUD"
        const val DEBUG_HOST = "10.0.2.2"
        const val DEBUG_PORT = 8081
        val DEBUG_MODE = BuildConfig.DEBUG && isEmulator()
        lateinit var instance: GlassesApp
            private set

        private fun isEmulator(): Boolean =
            Build.FINGERPRINT.contains("generic") ||
                Build.FINGERPRINT.contains("emulator") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for") ||
                Build.MODEL.contains("sdk_gphone") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.PRODUCT.contains("sdk") ||
                Build.PRODUCT.contains("emulator")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Clawsses HUD initialized")
        phoneConnection = PhoneConnectionService(
            debugMode = DEBUG_MODE,
            debugHost = DEBUG_HOST,
            debugPort = DEBUG_PORT,
        ).also(PhoneConnectionService::startListening)
        Log.i(TAG, "Started process-scoped phone bridge")
    }

    override fun onTerminate() {
        phoneConnection.stop()
        super.onTerminate()
    }
}
