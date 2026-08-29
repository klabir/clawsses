package com.clawsses.phone.runtime

import android.content.Context
import android.util.Log
import com.clawsses.phone.audio.AndroidSpeechAudioFocusController
import com.clawsses.phone.audio.AudioSessionCoordinator
import com.clawsses.phone.glasses.ApkInstaller
import com.clawsses.phone.glasses.GlassesConnectionManager
import com.clawsses.phone.media.PendingPhotoRepository
import com.clawsses.phone.media.ChatAttachmentFileStore
import com.clawsses.phone.openclaw.DeviceIdentity
import com.clawsses.phone.openclaw.AndroidNetworkMonitor
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

/**
 * Process-scoped runtime graph. Vendor SDK, audio, recognition, and gateway clients must not be
 * recreated when Compose or the Activity is recreated.
 */
class ClawssesRuntime(context: Context) {
    private val appContext = context.applicationContext

    val glassesManager = GlassesConnectionManager(appContext)
    val chatAttachmentFileStore = ChatAttachmentFileStore(appContext)
    val openClawClient = OpenClawClient(
        DeviceIdentity(appContext),
        AndroidNetworkMonitor(appContext),
        chatAttachmentFileStore,
    )
    val voiceHandler = VoiceCommandHandler(appContext)
    val voiceLanguageManager = VoiceLanguageManager(appContext)
    val voiceRecognitionManager = VoiceRecognitionManager(appContext)
    val talkModeManager = TalkModeManager(appContext)
    val apkInstaller = ApkInstaller(appContext, glassesManager)
    val ttsSettingsManager = TtsSettingsManager(appContext)
    val elevenLabsClient = ElevenLabsClient()
    val openAiTtsClient = OpenAiTtsClient()
    val audioSessionCoordinator = AudioSessionCoordinator(
        AndroidSpeechAudioFocusController(appContext),
    )
    val liveCaptionManager = LiveCaptionManager(appContext, audioSessionCoordinator)
    val ttsPlaybackManager = TtsPlaybackManager(
        appContext,
        elevenLabsClient,
        openAiTtsClient,
        ttsSettingsManager,
        audioSessionCoordinator,
    )

    val pendingPhotoRepository = PendingPhotoRepository(appContext)
    val pendingPhotos = pendingPhotoRepository.photos

    val talkCoordinator = TalkRuntimeCoordinator(
        context = appContext,
        glassesManager = glassesManager,
        openClawClient = openClawClient,
        voiceHandler = voiceHandler,
        voiceLanguageManager = voiceLanguageManager,
        voiceRecognitionManager = voiceRecognitionManager,
        talkModeManager = talkModeManager,
        ttsPlaybackManager = ttsPlaybackManager,
        audioSessionCoordinator = audioSessionCoordinator,
        pendingPhotoRepository = pendingPhotoRepository,
    )

    val stagedVoiceCoordinator = StagedVoiceCoordinator(
        glassesManager = glassesManager,
        voiceHandler = voiceHandler,
        voiceLanguageManager = voiceLanguageManager,
        voiceRecognitionManager = voiceRecognitionManager,
        audioSessionCoordinator = audioSessionCoordinator,
        stopCurrentTtsOutput = talkCoordinator::stopCurrentTtsOutput,
    )

    val phoneGlassesBridge = PhoneGlassesBridgeController(
        context = appContext,
        glassesManager = glassesManager,
        openClawClient = openClawClient,
        voiceLanguageManager = voiceLanguageManager,
        voiceRecognitionManager = voiceRecognitionManager,
        liveCaptionManager = liveCaptionManager,
        talkModeManager = talkModeManager,
        ttsSettingsManager = ttsSettingsManager,
        ttsPlaybackManager = ttsPlaybackManager,
        pendingPhotoRepository = pendingPhotoRepository,
        chatAttachmentFileStore = chatAttachmentFileStore,
        talkCoordinator = talkCoordinator,
        stagedVoiceCoordinator = stagedVoiceCoordinator,
    )

    fun start() {
        if (BenchmarkIsolation.isActive(appContext)) {
            Log.i(TAG, "Benchmark mode: external process runtime disabled")
            return
        }
        talkCoordinator.start()
        phoneGlassesBridge.start()
    }

    fun cleanup() {
        phoneGlassesBridge.cleanup()
        talkCoordinator.cleanup()
        glassesManager.dispose()
        openClawClient.cleanup()
        audioSessionCoordinator.clear()
        voiceHandler.cleanup()
        voiceRecognitionManager.cleanup()
        liveCaptionManager.cleanup()
        ttsPlaybackManager.dispose()
        pendingPhotoRepository.close()
    }

    private companion object {
        const val TAG = "ClawssesRuntime"
    }
}
