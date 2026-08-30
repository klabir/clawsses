package com.clawsses.phone.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioSessionOwner {
    WAKE_WORD,
    CAPTURE,
    PLAYBACK,
}

class AudioSessionLease internal constructor(
    val owner: AudioSessionOwner,
    val generation: Long,
)

interface SpeechAudioFocusController {
    fun request(onFocusLost: () -> Unit): Boolean
    fun abandon()
}

/** Owns the process-wide microphone/playback lease and Android speech-output focus. */
class AudioSessionCoordinator(
    private val focusController: SpeechAudioFocusController,
) {
    private var generation = 0L
    private var activeLease: AudioSessionLease? = null
    private var wakeWordPreempt: (() -> Unit)? = null
    private val mutableActiveOwner = MutableStateFlow<AudioSessionOwner?>(null)
    val activeOwner: StateFlow<AudioSessionOwner?> = mutableActiveOwner.asStateFlow()

    @Synchronized
    fun beginCapture(): AudioSessionLease? {
        preemptWakeWordLocked()?.invoke()
        if (activeLease != null) return null
        return newLease(AudioSessionOwner.CAPTURE)
    }

    @Synchronized
    fun beginWakeWord(onPreempt: () -> Unit): AudioSessionLease? {
        if (activeLease != null) return null
        wakeWordPreempt = onPreempt
        return newLease(AudioSessionOwner.WAKE_WORD)
    }

    @Synchronized
    fun beginPlayback(onFocusLost: () -> Unit): AudioSessionLease? {
        preemptWakeWordLocked()?.invoke()
        if (activeLease != null) return null
        val lease = newLease(AudioSessionOwner.PLAYBACK)
        val granted = focusController.request {
            val shouldNotify = synchronized(this) {
                if (activeLease != lease) false
                else {
                    activeLease = null
                    mutableActiveOwner.value = null
                    true
                }
            }
            if (shouldNotify) {
                focusController.abandon()
                onFocusLost()
            }
        }
        if (granted) return lease
        if (activeLease == lease) activeLease = null
        return null
    }

    @Synchronized
    fun isCurrent(lease: AudioSessionLease): Boolean = activeLease == lease

    fun release(lease: AudioSessionLease): Boolean {
        val abandonFocus = synchronized(this) {
            if (activeLease != lease) return false
            activeLease = null
            if (lease.owner == AudioSessionOwner.WAKE_WORD) wakeWordPreempt = null
            mutableActiveOwner.value = null
            lease.owner == AudioSessionOwner.PLAYBACK
        }
        if (abandonFocus) focusController.abandon()
        return true
    }

    fun clear() {
        var preempt: (() -> Unit)? = null
        val abandonFocus = synchronized(this) {
            val hadPlayback = activeLease?.owner == AudioSessionOwner.PLAYBACK
            if (activeLease?.owner == AudioSessionOwner.WAKE_WORD) {
                preempt = wakeWordPreempt
            }
            wakeWordPreempt = null
            generation += 1
            activeLease = null
            mutableActiveOwner.value = null
            hadPlayback
        }
        preempt?.invoke()
        if (abandonFocus) focusController.abandon()
    }

    @Synchronized
    private fun newLease(owner: AudioSessionOwner): AudioSessionLease {
        generation += 1
        return AudioSessionLease(owner, generation).also {
            activeLease = it
            mutableActiveOwner.value = owner
        }
    }

    private fun preemptWakeWordLocked(): (() -> Unit)? {
        if (activeLease?.owner != AudioSessionOwner.WAKE_WORD) return null
        val callback = wakeWordPreempt
        wakeWordPreempt = null
        generation += 1
        activeLease = null
        mutableActiveOwner.value = null
        return callback
    }
}

class AndroidSpeechAudioFocusController(context: Context) : SpeechAudioFocusController {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var activeRequest: AudioFocusRequest? = null

    @Synchronized
    override fun request(onFocusLost: () -> Unit): Boolean {
        abandon()
        val manager = audioManager ?: return false
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(
                { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    ) onFocusLost()
                },
                Handler(Looper.getMainLooper()),
            )
            .build()
        return if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            activeRequest = request
            true
        } else {
            false
        }
    }

    @Synchronized
    override fun abandon() {
        val request = activeRequest ?: return
        activeRequest = null
        audioManager?.abandonAudioFocusRequest(request)
    }
}
