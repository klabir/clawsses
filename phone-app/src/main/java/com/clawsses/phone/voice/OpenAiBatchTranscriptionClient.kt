package com.clawsses.phone.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Records one bounded Phone-microphone utterance to app cache and submits it to OpenAI's
 * transcription endpoint. Audio is never accumulated in memory and every terminal path removes
 * the temporary WAV file.
 */
internal class OpenAiBatchTranscriptionClient(
    private val cacheDirectory: File,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private data class CaptureSession(
        val id: Long,
        val apiKey: String,
        val languageTag: String?,
        val file: File,
        val output: FileOutputStream,
        val recorder: AudioRecord,
        val onSpeechStopped: () -> Unit,
        val onFinal: (String) -> Unit,
        val onError: (String) -> Unit,
        val recording: AtomicBoolean = AtomicBoolean(true),
        val finishing: AtomicBoolean = AtomicBoolean(false),
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val call: AtomicReference<Call?> = AtomicReference(null),
        var pcmByteCount: Long = 0L,
        var captureJob: Job? = null,
        var timeoutJob: Job? = null,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sequence = AtomicLong(0L)
    private val active = AtomicReference<CaptureSession?>(null)
    private val audioFormat = BatchAudioFormat()
    private val maxPcmBytes = audioFormat.maxPcmBytes(MAX_RECORDING_MS)

    init {
        cacheDirectory.mkdirs()
        cacheDirectory.listFiles { file -> file.name.startsWith(FILE_PREFIX) }
            ?.forEach(File::delete)
    }

    @SuppressLint("MissingPermission")
    fun startListening(
        apiKey: String,
        languageTag: String?,
        onSpeechStopped: () -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        cancelListening()
        if (apiKey.isBlank()) {
            mainHandler.post { onError("Long dictation requires an OpenAI API key") }
            return
        }

        val minimumBuffer = AudioRecord.getMinBufferSize(
            audioFormat.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) {
            mainHandler.post { onError("Unable to determine microphone buffer size") }
            return
        }

        val file = runCatching {
            cacheDirectory.mkdirs()
            File.createTempFile(FILE_PREFIX, ".wav", cacheDirectory)
        }.getOrElse {
            mainHandler.post { onError("Unable to create the temporary dictation file") }
            return
        }
        val output = runCatching {
            FileOutputStream(file).apply { write(audioFormat.wavHeader(0)) }
        }.getOrElse {
            file.delete()
            mainHandler.post { onError("Unable to open the temporary dictation file") }
            return
        }
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                audioFormat.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minimumBuffer * BUFFER_MULTIPLIER,
            )
        }.getOrElse {
            output.close()
            file.delete()
            mainHandler.post { onError("Unable to initialize the microphone") }
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            output.close()
            file.delete()
            mainHandler.post { onError("Unable to initialize the microphone") }
            return
        }

        val session = CaptureSession(
            id = sequence.incrementAndGet(),
            apiKey = apiKey,
            languageTag = languageTag,
            file = file,
            output = output,
            recorder = recorder,
            onSpeechStopped = onSpeechStopped,
            onFinal = onFinal,
            onError = onError,
        )
        active.set(session)
        try {
            recorder.startRecording()
        } catch (_: Exception) {
            finish(session, transcribe = false, error = "Unable to start microphone capture")
            return
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            capture(session, minimumBuffer * BUFFER_MULTIPLIER)
        }
        session.captureJob = job
        job.start()
        session.timeoutJob = scope.launch {
            delay(MAX_RECORDING_MS)
            finish(session, transcribe = true, notifySpeechStopped = true)
        }
    }

    fun stopListening() {
        active.get()?.let { session ->
            finish(session, transcribe = true, notifySpeechStopped = true)
        }
    }

    fun cancelListening() {
        active.get()?.let { session ->
            session.cancelled.set(true)
            session.call.getAndSet(null)?.cancel()
            if (session.finishing.get()) {
                cleanup(session)
            } else {
                finish(session, transcribe = false)
            }
        }
    }

    fun destroy() {
        active.getAndSet(null)?.let { session ->
            session.cancelled.set(true)
            session.call.getAndSet(null)?.cancel()
            session.recording.set(false)
            session.finishing.set(true)
            session.timeoutJob?.cancel()
            runCatching { session.recorder.stop() }
            runCatching { session.recorder.release() }
            runCatching { session.output.close() }
            session.file.delete()
        }
        scope.cancel()
    }

    private suspend fun capture(session: CaptureSession, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        while (scope.isActive && session.recording.get()) {
            val count = runCatching {
                session.recorder.read(buffer, 0, buffer.size)
            }.getOrElse {
                if (!session.finishing.get()) {
                    finish(session, transcribe = false, error = "Microphone capture failed")
                }
                return
            }
            if (count <= 0) {
                if (!session.finishing.get()) {
                    finish(session, transcribe = false, error = "Microphone capture stopped unexpectedly")
                }
                return
            }

            val remaining = maxPcmBytes - session.pcmByteCount
            if (remaining <= 0L) {
                finish(session, transcribe = true, notifySpeechStopped = true)
                return
            }
            val accepted = minOf(count.toLong(), remaining).toInt()
            runCatching { session.output.write(buffer, 0, accepted) }.getOrElse {
                finish(session, transcribe = false, error = "Unable to store dictation audio")
                return
            }
            session.pcmByteCount += accepted
            if (accepted < count || session.pcmByteCount >= maxPcmBytes) {
                finish(session, transcribe = true, notifySpeechStopped = true)
                return
            }
        }
    }

    private fun finish(
        session: CaptureSession,
        transcribe: Boolean,
        notifySpeechStopped: Boolean = false,
        error: String? = null,
    ) {
        if (!session.finishing.compareAndSet(false, true)) return
        session.recording.set(false)
        session.timeoutJob?.cancel()
        runCatching { session.recorder.stop() }
        if (notifySpeechStopped && active.get() === session) {
            mainHandler.post {
                if (active.get() === session) session.onSpeechStopped()
            }
        }
        scope.launch {
            session.captureJob?.join()
            runCatching { session.output.flush() }
            runCatching { session.output.close() }
            runCatching { session.recorder.release() }

            when {
                active.get() !== session -> session.file.delete()
                error != null -> deliverError(session, error)
                !transcribe -> cleanup(session)
                session.pcmByteCount == 0L -> deliverFinal(session, "")
                else -> {
                    val prepared = runCatching {
                        RandomAccessFile(session.file, "rw").use { wav ->
                            wav.seek(0L)
                            wav.write(audioFormat.wavHeader(session.pcmByteCount))
                        }
                    }.isSuccess
                    if (!prepared) {
                        deliverError(session, "Unable to finalize dictation audio")
                    } else {
                        transcribe(session)
                    }
                }
            }
        }
    }

    private fun transcribe(session: CaptureSession) {
        if (session.cancelled.get() || active.get() !== session) {
            cleanup(session)
            return
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart(
                "file",
                "dictation.wav",
                session.file.asRequestBody("audio/wav".toMediaType()),
            )
            .apply {
                transcriptionLanguage(session.languageTag)?.let { language ->
                    addFormDataPart("language", language)
                }
            }
            .build()
        val request = Request.Builder()
            .url(TRANSCRIPTIONS_URL)
            .header("Authorization", "Bearer ${session.apiKey}")
            .post(body)
            .build()

        val call = httpClient.newCall(request)
        session.call.set(call)
        if (session.cancelled.get() || active.get() !== session) {
            call.cancel()
            session.call.compareAndSet(call, null)
            cleanup(session)
            return
        }
        val result = runCatching {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}")
                }
                val payload = response.body?.string().orEmpty()
                JSONObject(payload).optString("text").takeIf(String::isNotBlank)
                    ?: error("Transcription response did not contain text")
            }
        }
        session.call.compareAndSet(call, null)
        if (session.cancelled.get() || active.get() !== session) {
            cleanup(session)
            return
        }
        result.onSuccess { text -> deliverFinal(session, text) }
            .onFailure { failure ->
                val detail = failure.message?.takeIf { it.startsWith("HTTP ") }
                deliverError(
                    session,
                    if (detail != null) "Long dictation failed ($detail)" else "Long dictation failed",
                )
            }
    }

    private fun deliverFinal(session: CaptureSession, text: String) {
        if (active.get() !== session) {
            session.file.delete()
            return
        }
        mainHandler.post {
            if (active.get() === session) session.onFinal(text)
            cleanup(session)
        }
    }

    private fun deliverError(session: CaptureSession, message: String) {
        if (active.get() !== session) {
            session.file.delete()
            return
        }
        mainHandler.post {
            if (active.get() === session) session.onError(message)
            cleanup(session)
        }
    }

    private fun cleanup(session: CaptureSession) {
        active.compareAndSet(session, null)
        session.file.delete()
    }

    companion object {
        const val MAX_RECORDING_MS = 5 * 60 * 1_000L
        private const val BUFFER_MULTIPLIER = 2
        private const val FILE_PREFIX = "clawsses_dictation_"
        private const val MODEL = "gpt-4o-transcribe"
        private const val TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"

        private fun defaultHttpClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
