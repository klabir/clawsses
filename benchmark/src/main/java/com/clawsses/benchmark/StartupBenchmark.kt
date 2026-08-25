package com.clawsses.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    @Test fun coldStart() = measure(StartupMode.COLD)

    @Test fun warmStart() = measure(StartupMode.WARM)

    private fun measure(mode: StartupMode) = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = mode,
        iterations = 5,
        setupBlock = {
            grantRuntimePermissions()
            pressHome()
        },
    ) {
        startActivityAndWait()
        device.waitForIdle()
    }
}

private fun androidx.benchmark.macro.MacrobenchmarkScope.grantRuntimePermissions() {
    REQUIRED_PERMISSIONS.forEach { permission ->
        device.executeShellCommand("pm grant $PACKAGE_NAME $permission")
    }
}

private val REQUIRED_PERMISSIONS = listOf(
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.RECORD_AUDIO",
    "android.permission.NEARBY_WIFI_DEVICES",
)

private const val PACKAGE_NAME = "com.clawsses.phone"
