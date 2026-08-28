package com.clawsses.phone

import android.Manifest
import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.clawsses.phone.ui.theme.ClawssesTheme
import com.clawsses.phone.ui.screens.MainScreen
import com.clawsses.phone.service.GlassesConnectionService
import com.clawsses.shared.TechnicalJankMonitor

class MainActivity : ComponentActivity() {
    private lateinit var jankMonitor: TechnicalJankMonitor
    private var appInitialized = false

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startConnectionServiceIfPermitted()
    }

    private val hiRokidAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        (application as ClawssesApp).runtime.apkInstaller.handleHiRokidAuthorization(
            result.resultCode,
            result.data,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        jankMonitor = TechnicalJankMonitor(window, "ClawssesPerf", "phone")
        (application as ClawssesApp).runtime.apkInstaller.launchHiRokidAuthorization =
            hiRokidAuthorizationLauncher::launch

        // Render the text/OpenClaw shell regardless of optional hardware capability grants.
        initializeApp()
        val missing = requiredPermissions.filterNot(::hasPermission)
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        jankMonitor.onResume()
    }

    override fun onPause() {
        jankMonitor.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        (application as ClawssesApp).runtime.apkInstaller.launchHiRokidAuthorization = null
        jankMonitor.close()
        super.onDestroy()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun initializeApp() {
        if (appInitialized) return
        appInitialized = true
        (application as ClawssesApp).runtime.start()
        startConnectionServiceIfPermitted()
        setContent {
            ClawssesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    private fun startConnectionServiceIfPermitted() {
        val bluetoothAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH) &&
                hasPermission(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (bluetoothAllowed && com.clawsses.phone.glasses.RokidSdkManager.hasSavedConnectionInfo()) {
            GlassesConnectionService.start(this)
        }
    }
}
