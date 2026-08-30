package com.clawsses.phone

import android.app.Application
import android.util.Log
import com.clawsses.phone.glasses.RokidSdkManager
import com.clawsses.phone.glasses.RokidVendorLogPolicy
import com.clawsses.phone.runtime.BenchmarkIsolation
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

        if (BenchmarkIsolation.isActive(this)) {
            Log.i(TAG, "Benchmark mode: external Rokid runtime disabled")
        } else {
            // CXR-M 1.2.2 logs credential-bearing arguments at INFO. Keep the vendor logger at
            // WARN for the lifetime of every production-capable process; restoring INFO after an
            // asynchronous SDK call would reopen the leak in later callbacks.
            // Initialize Rokid SDK only for production-capable variants. Benchmark variants must
            // never claim the live glasses connection while collecting local startup profiles.
            if (RokidVendorLogPolicy.applyBefore { RokidSdkManager.initialize(this) }) {
                Log.d(TAG, "Rokid SDK initialized successfully")
            } else {
                Log.w(TAG, "Rokid SDK initialization failed - check rokid.accessKey in local.properties")
            }
        }
        runtime = ClawssesRuntime(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        runtime.cleanup()
        RokidSdkManager.cleanup()
    }
}
