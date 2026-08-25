package com.clawsses.shared

import android.util.Log
import android.view.Window
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState

/** Records frame timing metadata only. It never receives chat or credential data. */
class TechnicalJankMonitor(
    window: Window,
    private val logTag: String,
    surface: String,
) {
    private val metricsState = PerformanceMetricsState.getHolderForHierarchy(window.decorView)
    private val jankStats = JankStats.createAndTrack(window) { frame ->
        if (frame.isJank) {
            Log.w(
                logTag,
                "Jank surface=$surface durationMs=${frame.frameDurationUiNanos / 1_000_000}",
            )
        }
    }

    init {
        metricsState.state?.putState("surface", surface)
    }

    fun onResume() {
        jankStats.isTrackingEnabled = true
    }

    fun onPause() {
        jankStats.isTrackingEnabled = false
    }

    fun close() {
        jankStats.isTrackingEnabled = false
        metricsState.state?.removeState("surface")
    }
}
