package com.clawsses.phone.runtime

import android.content.Context

/** Prevents profiling packages from claiming production hardware or network lifecycles. */
object BenchmarkIsolation {
    fun isActive(context: Context): Boolean = isBenchmarkPackage(context.packageName)

    fun isBenchmarkPackage(packageName: String): Boolean =
        packageName.endsWith(BENCHMARK_PACKAGE_SUFFIX)

    private const val BENCHMARK_PACKAGE_SUFFIX = ".benchmark"
}
