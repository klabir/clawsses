package com.clawsses.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val baselineProfileRule = BaselineProfileRule()

    @Test fun generate() {
        val targetPackage = benchmarkTargetPackage()
        clearBenchmarkTarget(targetPackage)
        baselineProfileRule.collect(
            packageName = targetPackage,
            includeInStartupProfile = true,
        ) {
            REQUIRED_PERMISSIONS.forEach { permission ->
                device.executeShellCommand("pm grant $targetPackage $permission")
            }
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
        }
    }
}

private val REQUIRED_PERMISSIONS = listOf(
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.RECORD_AUDIO",
    "android.permission.NEARBY_WIFI_DEVICES",
)
