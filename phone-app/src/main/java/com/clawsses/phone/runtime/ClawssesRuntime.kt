package com.clawsses.phone.runtime

import android.content.Context
import com.clawsses.phone.glasses.ApkInstaller
import com.clawsses.phone.glasses.GlassesConnectionManager
import com.clawsses.phone.openclaw.DeviceIdentity
import com.clawsses.phone.openclaw.OpenClawClient
import com.clawsses.phone.talk.TalkModeManager
import com.clawsses.phone.tts.ElevenLabsClient
import com.clawsses.phone.tts.OpenAiTtsClient
import com.clawsses.phone.tts.TtsPlaybackManager
import com.clawsses.phone.tts.TtsSettingsManager
import com.clawsses.phone.voice.LiveCaptionManager
import com.clawsses.phone.voice.VoiceCommandHandler
import com.clawsses.phone.voice.VoiceLanguageManager
import com.clawsses.phone.voice.VoiceRecognitionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped runtime graph. Vendor SDK, audio, recognition, and gateway clients must not be
 * recreated when Compose or the Activity is recreated.
 */
class ClawssesRuntime(context: Context) {
    private val appContext = context.applicationContext

    val glassesManager = GlassesConnectionManager(appContext)
    val openClawClient = OpenClawClient(DeviceIdentity(appContext))
    val voiceHandler = VoiceCommandHandler(appContext)
    val voiceLanguageManager = VoiceLanguageManager(appContext)
    val voiceRecognitionManager = VoiceRecognitionManager(appContext)
    val liveCaptionManager = LiveCaptionManager(appContext)
    val talkModeManager = TalkModeManager(appContext)
    val apkInstaller = ApkInstaller(appContext)
    val ttsSettingsManager = TtsSettingsManager(appContext)
    val elevenLabsClient = ElevenLabsClient()
    val openAiTtsClient = OpenAiTtsClient()
    val ttsPlaybackManager = TtsPlaybackManager(
        appContext,
        elevenLabsClient,
        openAiTtsClient,
        ttsSettingsManager,
    )

    private val _pendingPhotos = MutableStateFlow<List<String>>(emptyList())
    val pendingPhotos: StateFlow<List<String>> = _pendingPhotos.asStateFlow()

    val talkCoordinator = TalkRuntimeCoordinator(
        context = appContext,
        glassesManager = glassesManager,
        openClawClient = openClawClient,
        voiceHandler = voiceHandler,
        voiceLanguageManager = voiceLanguageManager,
        voiceRecognitionManager = voiceRecognitionManager,
        talkModeManager = talkModeManager,
        ttsPlaybackManager = ttsPlaybackManager,
        pendingPhotos = _pendingPhotos,
    )

    fun start() = talkCoordinator.start()

    fun replacePendingPhotos(photos: List<String>) {
        _pendingPhotos.value = photos
    }

    fun addPendingPhoto(photo: String) {
        _pendingPhotos.value = _pendingPhotos.value + photo
    }

    fun removePendingPhoto(index: Int) {
        val current = _pendingPhotos.value
        if (index !in current.indices) return
        _pendingPhotos.value = current.toMutableList().apply { removeAt(index) }
    }

    fun cleanup() {
        talkCoordinator.cleanup()
        glassesManager.dispose()
        openClawClient.cleanup()
        voiceHandler.cleanup()
        voiceRecognitionManager.cleanup()
        liveCaptionManager.cleanup()
        ttsPlaybackManager.dispose()
    }
}
