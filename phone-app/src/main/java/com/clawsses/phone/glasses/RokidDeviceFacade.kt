package com.clawsses.phone.glasses

import com.rokid.cxr.client.utils.ValueUtil

/**
 * Narrow application-facing boundary around the unchanged vendor SDK adapter.
 *
 * Connection setup, callback ordering, timeouts, installer fallbacks and every CXR-M invocation
 * remain owned by [RokidSdkManager]. Runtime features depend on this facade so vendor types and
 * global callbacks do not spread further through the application and can be replaced by fakes in
 * JVM tests.
 */
interface RokidDeviceFacade {
    var onPhotoResult: ((ValueUtil.CxrStatus?, ByteArray?) -> Unit)?
    var onAudioStreamStarted: ((Int, Int, Int, String) -> Unit)?
    var onAudioStreamData: ((ByteArray, Int, Int) -> Unit)?
    var onAudioStreamFinished: (() -> Unit)?

    fun isConnected(): Boolean
    fun hasSavedConnectionInfo(): Boolean
    fun setCommunicationDevice()
    fun clearCommunicationDevice()
    fun startMicrophoneStream(): Boolean
    fun stopMicrophoneStream()
    fun clearMicrophoneStreamCallbacks()
    fun sendAsrContent(content: String): ValueUtil.CxrStatus?
    fun notifyAsrNone(): ValueUtil.CxrStatus?
    fun notifyAsrError(): ValueUtil.CxrStatus?
    fun notifyAsrEnd(): ValueUtil.CxrStatus?
    fun sendExitEvent(): ValueUtil.CxrStatus?
    fun takePhoto(width: Int, height: Int, quality: Int): ValueUtil.CxrStatus?
}

object ProductionRokidDeviceFacade : RokidDeviceFacade {
    override var onPhotoResult: ((ValueUtil.CxrStatus?, ByteArray?) -> Unit)?
        get() = RokidSdkManager.onPhotoResult
        set(value) { RokidSdkManager.onPhotoResult = value }
    override var onAudioStreamStarted: ((Int, Int, Int, String) -> Unit)?
        get() = RokidSdkManager.onAudioStreamStarted
        set(value) { RokidSdkManager.onAudioStreamStarted = value }
    override var onAudioStreamData: ((ByteArray, Int, Int) -> Unit)?
        get() = RokidSdkManager.onAudioStreamData
        set(value) { RokidSdkManager.onAudioStreamData = value }
    override var onAudioStreamFinished: (() -> Unit)?
        get() = RokidSdkManager.onAudioStreamFinished
        set(value) { RokidSdkManager.onAudioStreamFinished = value }

    override fun isConnected() = RokidSdkManager.isConnected()
    override fun hasSavedConnectionInfo() = RokidSdkManager.hasSavedConnectionInfo()
    override fun setCommunicationDevice() = RokidSdkManager.setCommunicationDevice()
    override fun clearCommunicationDevice() = RokidSdkManager.clearCommunicationDevice()
    override fun startMicrophoneStream() = RokidSdkManager.startMicrophoneStream()
    override fun stopMicrophoneStream() = RokidSdkManager.stopMicrophoneStream()
    override fun clearMicrophoneStreamCallbacks() = RokidSdkManager.clearMicrophoneStreamCallbacks()
    override fun sendAsrContent(content: String) = RokidSdkManager.sendAsrContent(content)
    override fun notifyAsrNone() = RokidSdkManager.notifyAsrNone()
    override fun notifyAsrError() = RokidSdkManager.notifyAsrError()
    override fun notifyAsrEnd() = RokidSdkManager.notifyAsrEnd()
    override fun sendExitEvent() = RokidSdkManager.sendExitEvent()
    override fun takePhoto(width: Int, height: Int, quality: Int) =
        RokidSdkManager.takeGlassPhotoGlobal(width, height, quality)
}
