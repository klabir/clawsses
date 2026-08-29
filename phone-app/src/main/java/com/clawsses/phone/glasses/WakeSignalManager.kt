package com.clawsses.phone.glasses

import android.util.Log
import android.os.SystemClock
import com.clawsses.shared.WakeSignal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class WakeLogLevel { DEBUG, INFO, WARN }

private fun logWakeSignal(level: WakeLogLevel, message: String) {
    when (level) {
        WakeLogLevel.DEBUG -> Log.d("WakeSignalManager", message)
        WakeLogLevel.INFO -> Log.i("WakeSignalManager", message)
        WakeLogLevel.WARN -> Log.w("WakeSignalManager", message)
    }
}

/**
 * Manages wake signal coordination between phone and glasses.
 *
 * When the glasses may be in standby (display off), this manager:
 * 1. Wakes the hardware display via CXR-M SDK (setGlassBrightness from phone side)
 * 2. Sends a wake signal message to glasses for notification UI
 * 3. Gates the single outbound transport queue until the display responds
 * 4. Handles timeout and retry logic without owning a second message buffer
 *
 * The Rokid micro-LED display is controlled from the phone via CXR SDK — Android
 * PowerManager on the glasses does NOT work. The phone calls setGlassBrightness()
 * and setScreenOffTimeout() to physically turn on the display.
 */
class WakeSignalManager(
    private val enqueueToGlasses: (String, Boolean) -> Unit,
    private val setDeliveryAllowed: (Boolean) -> Unit,
    private val pendingMessageCount: () -> Int = { 0 },
    private val wakeHardwareDisplay: () -> Boolean = { false },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val logger: (WakeLogLevel, String) -> Unit = ::logWakeSignal,
    private val monotonicClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wakeAckTimeoutMs: Long = WAKE_ACK_TIMEOUT_MS,
    private val standbyDetectionMs: Long = STANDBY_DETECTION_MS,
    private val persistentWakeIntervalMs: Long = PERSISTENT_WAKE_INTERVAL_MS,
) {
    companion object {
        // Timeout waiting for wake acknowledgment
        private const val WAKE_ACK_TIMEOUT_MS = 3000L

        // Minimum interval between wake signals to avoid spam
        private const val MIN_WAKE_INTERVAL_MS = 1000L

        // Time after last confirmed activity before assuming glasses may be in standby.
        // Slightly less than the 30s screen-off timeout to be conservative.
        private const val STANDBY_DETECTION_MS = 25_000L

        // Minimum interval between hardware wake keep-alive calls during streaming.
        // Each call resets the glasses' 30s screen-off timeout via CXR SDK.
        private const val WAKE_KEEPALIVE_INTERVAL_MS = 5_000L

        // Direct CXR audio arrives in many small packets. Treat it as confirmed glasses activity,
        // but do not restart the standby timer or touch the vendor wake API for every packet.
        private const val AUDIO_ACTIVITY_INTERVAL_MS = 5_000L

        // Firmware 1.24 can enter a display/radio sleep state that does not forward the AI key or
        // wake word. While the user explicitly enables glasses Talk Mode, refresh the vendor
        // display timeout before it expires. Normal idle operation still uses standby.
        private const val PERSISTENT_WAKE_INTERVAL_MS = 20_000L
    }

    /**
     * Current wake state of the glasses
     */
    sealed class WakeState {
        /** Glasses is awake and ready to receive messages */
        object Awake : WakeState()

        /** Unknown state - glasses may be in standby */
        object Unknown : WakeState()

        /** Wake signal sent, waiting for acknowledgment */
        data class WakingUp(val reason: String, val sentAt: Long) : WakeState()
    }

    // Current wake state
    private val _wakeState = MutableStateFlow<WakeState>(WakeState.Unknown)
    val wakeState: StateFlow<WakeState> = _wakeState

    // Track last CONFIRMED activity from glasses (message received, wake_ack, connect).
    // NOT updated on outgoing messages — only on proof the glasses is responsive.
    private var lastConfirmedActivityTime = 0L

    // Track last hardware wake call to rate-limit keep-alives
    private var lastHardwareWakeTime = 0L

    // Track last wake signal time to avoid spam
    private var lastWakeSignalTime = 0L

    // Track the last direct-audio activity pulse separately from ordinary HUD traffic.
    private var lastAudioActivityTime = 0L

    // Track streaming state - if actively streaming, glasses should be awake
    private var isStreaming = false

    // Timeout job for wake acknowledgment
    private var wakeTimeoutJob: Job? = null

    // Standby detection timer — fires STANDBY_DETECTION_MS after last confirmed activity
    private var standbyTimerJob: Job? = null

    // Explicit Talk Mode keep-awake policy. It is active only while requested and connected.
    private var persistentWakeRequested = false
    private var glassesConnected = false
    private var persistentWakeJob: Job? = null

    // Feature toggle
    private var _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    /**
     * Enable or disable the wake signal feature.
     * When disabled, messages are sent directly without buffering.
     */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (!enabled) {
            wakeTimeoutJob?.cancel()
            standbyTimerJob?.cancel()
            setDeliveryAllowed(true)
        } else {
            setDeliveryAllowed(_wakeState.value is WakeState.Awake)
            if (_wakeState.value is WakeState.Awake) resetStandbyTimer()
        }
        logger(WakeLogLevel.INFO, "Wake signal feature ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Send a message to glasses, handling wake signal if needed.
     *
     * @param json The JSON message to send
     * @param isStreamContent True if this is part of an ongoing stream
     * @param isNewMessage True if this is a new spontaneous message (e.g., cron)
     * @return True if the message was sent immediately, false if buffered
     */
    fun sendMessage(
        json: String,
        isStreamContent: Boolean = false,
        isNewMessage: Boolean = false
    ): Boolean {
        // If feature is disabled, keep the single transport queue open.
        if (!_enabled.value) {
            enqueueToGlasses(json, false)
            return true
        }

        val now = monotonicClock()

        // Update streaming state
        if (isStreamContent) {
            isStreaming = true
        }

        return when (val state = _wakeState.value) {
            is WakeState.Awake -> {
                enqueueToGlasses(json, false)

                if (isStreamContent) {
                    // Reset standby detection — streaming counts as activity
                    resetStandbyTimer()

                    // Keep-alive: periodically reset glasses screen-off timeout
                    // via CXR SDK to prevent the 30s hardware timer from firing
                    if (now - lastHardwareWakeTime > WAKE_KEEPALIVE_INTERVAL_MS) {
                        logger(WakeLogLevel.DEBUG, "Stream keep-alive: resetting display timeout")
                        wakeHardwareDisplay()
                        lastHardwareWakeTime = now
                    }
                }

                true
            }

            is WakeState.WakingUp -> {
                enqueueToGlasses(json, false)
                false
            }

            is WakeState.Unknown -> {
                // Unknown state - glasses may be in standby
                val timeSinceLastActivity = now - lastConfirmedActivityTime

                if (lastConfirmedActivityTime > 0 && timeSinceLastActivity < 5000) {
                    // Very recent confirmed activity — glasses is likely still awake
                    setDeliveryAllowed(true)
                    enqueueToGlasses(json, false)
                    true
                } else {
                    // May be in standby — wake hardware and initiate wake protocol
                    val reason = when {
                        isStreamContent -> WakeSignal.REASON_STREAM_CONTENT
                        isNewMessage -> WakeSignal.REASON_CRON_MESSAGE
                        else -> WakeSignal.REASON_NEW_MESSAGE
                    }
                    initiateWake(reason)
                    enqueueToGlasses(json, false)
                    false
                }
            }
        }
    }

    /**
     * Notify that streaming has started for a message.
     * This prepares the wake manager for continuous streaming.
     */
    fun notifyStreamStart(messageId: String) {
        isStreaming = true

        // If in unknown state, proactively send wake signal
        if (_wakeState.value is WakeState.Unknown && _enabled.value) {
            initiateWake(WakeSignal.REASON_STREAM_CONTENT, messageId)
        } else if (_wakeState.value is WakeState.Awake && _enabled.value) {
            // Even if awake, wake the hardware as keep-alive for stream start
            val now = monotonicClock()
            if (now - lastHardwareWakeTime > WAKE_KEEPALIVE_INTERVAL_MS) {
                wakeHardwareDisplay()
                lastHardwareWakeTime = now
            }
        }
    }

    /**
     * Notify that streaming has ended for a message.
     */
    fun notifyStreamEnd(messageId: String) {
        isStreaming = false
    }

    /**
     * Handle wake acknowledgment from glasses.
     * This is called when glasses sends a wake_ack message.
     */
    fun handleWakeAck(ready: Boolean) {
        logger(WakeLogLevel.INFO, "Received wake acknowledgment: ready=$ready")

        wakeTimeoutJob?.cancel()
        lastConfirmedActivityTime = monotonicClock()

        if (ready) {
            _wakeState.value = WakeState.Awake
            setDeliveryAllowed(true)
            resetStandbyTimer()
        } else {
            // Wake failed - try again after a delay
            logger(WakeLogLevel.WARN, "Wake acknowledgment indicated failure, retrying...")
            _wakeState.value = WakeState.Unknown
            setDeliveryAllowed(false)
            scope.launch {
                delay(500)
                if (pendingMessageCount() > 0) {
                    initiateWake(WakeSignal.REASON_NEW_MESSAGE)
                }
            }
        }
    }

    /**
     * Handle activity from glasses (any message received).
     * This indicates glasses is awake and responsive.
     */
    fun handleGlassesActivity() {
        lastConfirmedActivityTime = monotonicClock()

        // If we were in unknown or waking state, mark as awake
        when (_wakeState.value) {
            is WakeState.Unknown, is WakeState.WakingUp -> {
                logger(WakeLogLevel.DEBUG, "Glasses activity detected, marking as awake")
                _wakeState.value = WakeState.Awake
                setDeliveryAllowed(true)
                wakeTimeoutJob?.cancel()
            }
            else -> {}
        }

        // Reset standby detection timer
        resetStandbyTimer()
    }

    /**
     * Handle proof-of-life from the direct CXR microphone stream.
     *
     * Audio packets prove that the glasses are awake even when no custom HUD command is sent.
     * Rate-limiting keeps this high-frequency path from creating a coroutine/job per packet while
     * still resetting both Clawsses' 25s standby detector and Rokid's 30s display timeout.
     */
    fun handleGlassesAudioActivity() {
        val now = monotonicClock()
        if (lastAudioActivityTime != 0L &&
            now - lastAudioActivityTime < AUDIO_ACTIVITY_INTERVAL_MS
        ) return

        lastAudioActivityTime = now
        handleGlassesActivity()
        if (_enabled.value && now - lastHardwareWakeTime >= WAKE_KEEPALIVE_INTERVAL_MS) {
            val woke = wakeHardwareDisplay()
            lastHardwareWakeTime = now
            logger(WakeLogLevel.DEBUG, "Audio keep-alive: hardware wake=$woke")
        }
    }

    /**
     * Notify that glasses has disconnected.
     * Reset state to unknown.
     */
    fun handleGlassesDisconnected() {
        glassesConnected = false
        persistentWakeJob?.cancel()
        persistentWakeJob = null
        _wakeState.value = WakeState.Unknown
        setDeliveryAllowed(false)
        wakeTimeoutJob?.cancel()
        standbyTimerJob?.cancel()
        lastConfirmedActivityTime = 0
        lastAudioActivityTime = 0L
        // The process transport retains non-transient messages for reconnect.
        logger(WakeLogLevel.DEBUG, "Glasses disconnected, state reset to Unknown")
    }

    /**
     * Notify that glasses has connected.
     * Reset to awake state and flush buffer.
     */
    fun handleGlassesConnected() {
        glassesConnected = true
        _wakeState.value = WakeState.Awake
        setDeliveryAllowed(true)
        lastConfirmedActivityTime = monotonicClock()
        lastHardwareWakeTime = monotonicClock()
        lastAudioActivityTime = 0L
        wakeTimeoutJob?.cancel()
        if (persistentWakeRequested) {
            startPersistentWakeLoop()
        } else {
            resetStandbyTimer()
        }

    }

    /**
     * Keep the Rokid display/CXR input path awake while glasses Talk Mode is explicitly enabled.
     * Firmware 1.24 otherwise stops forwarding AI-key and wake-word events after its 30s timeout.
     */
    fun setPersistentWakeEnabled(enabled: Boolean) {
        if (persistentWakeRequested == enabled) {
            if (enabled && glassesConnected) startPersistentWakeLoop()
            return
        }

        persistentWakeRequested = enabled
        if (enabled) {
            standbyTimerJob?.cancel()
            if (glassesConnected) {
                _wakeState.value = WakeState.Awake
                setDeliveryAllowed(true)
                startPersistentWakeLoop()
            }
        } else {
            persistentWakeJob?.cancel()
            persistentWakeJob = null
            if (glassesConnected && _wakeState.value is WakeState.Awake) resetStandbyTimer()
        }
        logger(WakeLogLevel.INFO, "Persistent glasses wake ${if (enabled) "enabled" else "disabled"}")
    }

    private fun startPersistentWakeLoop() {
        if (!persistentWakeRequested || !glassesConnected || persistentWakeJob?.isActive == true) return
        persistentWakeJob = scope.launch {
            while (isActive && persistentWakeRequested && glassesConnected) {
                delay(persistentWakeIntervalMs)
                if (!persistentWakeRequested || !glassesConnected) break
                val woke = wakeHardwareDisplay()
                lastHardwareWakeTime = monotonicClock()
                logger(WakeLogLevel.DEBUG, "Talk Mode keep-alive: hardware wake=$woke")
                if (woke) {
                    lastConfirmedActivityTime = lastHardwareWakeTime
                    _wakeState.value = WakeState.Awake
                    setDeliveryAllowed(true)
                }
            }
        }
    }

    /** Stop timers and buffered work owned by a disposed UI connection manager. */
    fun dispose() {
        wakeTimeoutJob?.cancel()
        standbyTimerJob?.cancel()
        persistentWakeJob?.cancel()
        scope.cancel()
    }

    private fun initiateWake(reason: String, messageId: String? = null) {
        val now = monotonicClock()

        // Rate limit wake signals
        if (now - lastWakeSignalTime < MIN_WAKE_INTERVAL_MS) {
            logger(WakeLogLevel.DEBUG, "Skipping wake signal (rate limited)")
            return
        }

        // Already waking up
        if (_wakeState.value is WakeState.WakingUp) {
            logger(WakeLogLevel.DEBUG, "Already waking up, skipping duplicate wake signal")
            return
        }

        lastWakeSignalTime = now
        _wakeState.value = WakeState.WakingUp(reason, now)
        setDeliveryAllowed(false)

        // Wake the hardware display from the phone side via CXR-M SDK.
        // This is the primary wake mechanism — setGlassBrightness() turns on
        // the micro-LED display, setScreenOffTimeout() resets the idle timer.
        val hwWakeResult = wakeHardwareDisplay()
        lastHardwareWakeTime = now
        logger(WakeLogLevel.INFO, "Hardware wake: $hwWakeResult")

        // Send wake signal message to glasses for notification UI
        val wakeSignal = WakeSignal(
            reason = reason,
            bufferedCount = pendingMessageCount(),
            messageId = messageId
        )
        logger(WakeLogLevel.INFO, "Sending wake signal: reason=$reason, queued=${pendingMessageCount()}")
        enqueueToGlasses(wakeSignal.toJson(), true)

        // Set timeout for wake acknowledgment
        wakeTimeoutJob?.cancel()
        wakeTimeoutJob = scope.launch {
            delay(wakeAckTimeoutMs)

            // If still waiting, assume glasses didn't receive the wake signal
            // or is offline. Deliver messages anyway — CXR bridge delivers even
            // when display is off, so content won't be lost.
            if (_wakeState.value is WakeState.WakingUp) {
                logger(WakeLogLevel.WARN, "Wake acknowledgment timeout, delivering messages anyway")
                _wakeState.value = WakeState.Unknown
                setDeliveryAllowed(true)
            }
        }
    }

    /**
     * Reset the standby detection timer.
     * After STANDBY_DETECTION_MS without confirmed activity from glasses,
     * transition from Awake to Unknown to detect potential standby.
     */
    private fun resetStandbyTimer() {
        standbyTimerJob?.cancel()
        if (!_enabled.value) return
        if (persistentWakeRequested && glassesConnected) return
        standbyTimerJob = scope.launch {
            delay(standbyDetectionMs)
            if (_enabled.value && _wakeState.value is WakeState.Awake) {
                logger(
                    WakeLogLevel.DEBUG,
                    "Standby detection: no activity for ${standbyDetectionMs}ms, marking Unknown",
                )
                _wakeState.value = WakeState.Unknown
                setDeliveryAllowed(false)
            }
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.cancel()
        wakeTimeoutJob?.cancel()
        standbyTimerJob?.cancel()
        persistentWakeJob?.cancel()
    }
}
