package com.clawsses.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
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
            iterations = 5,
            setupBlock = { pressHome() },
        ) {
            device.executeShellCommand(
                "am start -W -n $targetPackage/com.clawsses.phone.benchmark.ChatBenchmarkActivity",
            )
            check(device.wait(Until.hasObject(By.text("Run stream benchmark")), 5_000)) {
                "Benchmark workload did not become ready"
            }
            device.findObject(By.text("Run stream benchmark")).click()
            check(device.wait(Until.hasObject(By.text("Benchmark complete")), 15_000)) {
                "Benchmark workload did not complete"
            }
        }
    }
}
