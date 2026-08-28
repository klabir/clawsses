package com.clawsses.phone.runtime

internal class PhotoCaptureAttemptGate {
    sealed interface BeginResult {
        data class Started(val attemptId: Long) : BeginResult
        data object Busy : BeginResult
    }

    private var nextAttemptId = 0L
    private var activeAttemptId: Long? = null

    @Synchronized
    fun begin(): BeginResult {
        if (activeAttemptId != null) return BeginResult.Busy
        val attemptId = ++nextAttemptId
        activeAttemptId = attemptId
        return BeginResult.Started(attemptId)
    }

    @Synchronized
    fun complete(attemptId: Long): Boolean {
        if (activeAttemptId != attemptId) return false
        activeAttemptId = null
        return true
    }
}
