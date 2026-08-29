package com.clawsses.benchmark

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRenderingBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    @Test fun streamOneThousandUpdatesAcrossFiveHundredMessages() {
        val targetPackage = benchmarkTargetPackage()
        benchmarkRule.measureRepeated(
            packageName = targetPackage,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                device.wakeUp()
                device.executeShellCommand("wm dismiss-keyguard")
                pressHome()
            },
        ) {
            startActivityAndWait(
                Intent().setClassName(
                    targetPackage,
                    "com.clawsses.phone.benchmark.ChatBenchmarkActivity",
                ),
            )
            SystemClock.sleep(12_000)
            val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
            val statusUri = Uri.parse("content://com.clawsses.phone.benchmark.status/workload")
            val deadline = SystemClock.elapsedRealtime() + 8_000
            var completed = false
            while (!completed && SystemClock.elapsedRealtime() < deadline) {
                completed = resolver.query(statusUri, null, null, null, null)?.use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) == 1
                } == true
                if (!completed) SystemClock.sleep(250)
            }
            check(completed) {
                "Benchmark workload did not complete"
            }
        }
    }
}
