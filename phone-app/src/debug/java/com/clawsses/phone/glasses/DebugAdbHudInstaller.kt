package com.clawsses.phone.glasses

import android.content.Context
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Development-only ADB utility. No production code references this class. */
internal class DebugAdbHudInstaller(context: Context) {
    private val appContext = context.applicationContext

    suspend fun testConnection(host: String, port: Int = DEFAULT_ADB_PORT): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                Dadb.create(host, port).use { adb ->
                    val result = adb.shell("getprop ro.product.model")
                    check(result.exitCode == 0) { "ADB connection test failed" }
                    result.output.trim().ifEmpty { "Rokid HUD" }
                }
            }
        }

    suspend fun installBundledHud(host: String, port: Int = DEFAULT_ADB_PORT): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val apk = extractBundledHud()
                try {
                    Dadb.create(host, port).use { adb ->
                        val test = adb.shell("echo connected")
                        check(test.exitCode == 0) { "ADB connection test failed" }
                        adb.install(apk, "-r")
                    }
                } finally {
                    apk.delete()
                }
            }
        }

    private fun extractBundledHud(): File {
        val output = File.createTempFile("clawsses_debug_hud_", ".apk", appContext.cacheDir)
        try {
            appContext.assets.open(GLASSES_APP_ASSET).use { input ->
                FileOutputStream(output).use(input::copyTo)
            }
            check(output.length() > 0L) { "Bundled HUD APK is empty" }
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    private companion object {
        const val DEFAULT_ADB_PORT = 5555
        const val GLASSES_APP_ASSET = "glasses-app-release.apk"
    }
}
