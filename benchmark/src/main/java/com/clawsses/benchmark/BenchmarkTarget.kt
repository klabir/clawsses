package com.clawsses.benchmark

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

internal fun benchmarkTargetPackage(): String {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val testPackage = instrumentation.context.packageName
    val targetPackage = BuildConfig.TARGET_PACKAGE
    check(targetPackage.endsWith(BENCHMARK_PACKAGE_SUFFIX) && targetPackage != testPackage) {
        "Refusing unsafe benchmark target $targetPackage from test package $testPackage"
    }
    check(
        runCatching {
            instrumentation.context.packageManager.getPackageInfo(targetPackage, 0)
        }.isSuccess
    ) {
        "Isolated benchmark target is not installed: $targetPackage"
    }
    return targetPackage
}

internal fun clearBenchmarkTarget(packageName: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    UiDevice.getInstance(instrumentation).executeShellCommand("pm clear $packageName")
}

private const val BENCHMARK_PACKAGE_SUFFIX = ".benchmark"
