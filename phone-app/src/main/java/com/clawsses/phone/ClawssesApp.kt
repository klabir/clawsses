package com.clawsses.phone

import android.app.Application
import android.util.Log
import com.clawsses.phone.glasses.RokidSdkManager
import com.clawsses.phone.runtime.ClawssesRuntime

class ClawssesApp : Application() {

    lateinit var runtime: ClawssesRuntime
        private set

    companion object {
        const val TAG = "Clawsses"
        lateinit var instance: ClawssesApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Clawsses app initialized")

        // Initialize Rokid SDK
        if (RokidSdkManager.initialize(this)) {
            Log.d(TAG, "Rokid SDK initialized successfully")
        } else {
            Log.w(TAG, "Rokid SDK initialization failed - check rokid.accessKey in local.properties")
        }
        runtime = ClawssesRuntime(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        runtime.cleanup()
        RokidSdkManager.cleanup()
    }
}
