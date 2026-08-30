package com.clawsses.phone.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Native microphone/decoder boundary. Process policy and audio ownership remain outside it. */
internal class SherpaLocalWakeWordDetector(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong(0L)
    private val lock = Any()
    private var job: Job? = null
    private var recorder: AudioRecord? = null
    private var spotter: KeywordSpotter? = null

    fun start(onDetected: (String) -> Unit, onError: (String) -> Unit): Boolean = synchronized(lock) {
        if (job?.isActive == true) return false
        val activeGeneration = generation.incrementAndGet()
        job = scope.launch {
            detect(activeGeneration, onDetected, onError)
        }
        true
    }

    fun stop() {
        val activeRecorder = synchronized(lock) {
            generation.incrementAndGet()
            val current = recorder
            recorder = null
            job?.cancel()
            job = null
            current
        }
        runCatching { activeRecorder?.stop() }
        runCatching { activeRecorder?.release() }
    }

    fun destroy() {
        stop()
        synchronized(lock) {
            spotter?.release()
            spotter = null
        }
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private suspend fun detect(
        activeGeneration: Long,
        onDetected: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        var localRecorder: AudioRecord? = null
        var detected: String? = null
        var failure: String? = null
        val stream = runCatching { getOrCreateSpotter().createStream() }.getOrElse {
            postIfCurrent(activeGeneration) { onError("Local wake-word model failed to initialize") }
            clearJob(activeGeneration)
            return
        }
        try {
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimumBuffer > 0) { "Invalid microphone buffer" }
            localRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minimumBuffer * 2,
            )
            check(localRecorder.state == AudioRecord.STATE_INITIALIZED) {
                "Microphone did not initialize"
            }
            synchronized(lock) {
                if (generation.get() != activeGeneration) throw CancellationException()
                recorder = localRecorder
            }
            localRecorder.startRecording()
            val samples = ShortArray((SAMPLE_RATE * FRAME_MS) / 1_000)
            while (scope.isActive && generation.get() == activeGeneration) {
                val count = localRecorder.read(samples, 0, samples.size)
                check(count > 0) { "Microphone read failed" }
                stream.acceptWaveform(
                    FloatArray(count) { index -> samples[index] / 32768.0f },
                    SAMPLE_RATE,
                )
                while (getOrCreateSpotter().isReady(stream)) {
                    getOrCreateSpotter().decode(stream)
                    val keyword = getOrCreateSpotter().getResult(stream).keyword.trim()
                    if (keyword.isNotEmpty()) {
                        detected = keyword
                        break
                    }
                }
                if (detected != null) break
            }
        } catch (_: CancellationException) {
            // Expected when a foreground audio owner preempts local KWS.
        } catch (_: Throwable) {
            if (generation.get() == activeGeneration) failure = "Local wake-word microphone failed"
        } finally {
            synchronized(lock) {
                if (recorder === localRecorder) recorder = null
            }
            runCatching { localRecorder?.stop() }
            runCatching { localRecorder?.release() }
            runCatching { stream.release() }
            clearJob(activeGeneration)
        }
        when {
            detected != null -> postIfCurrent(activeGeneration) { onDetected(requireNotNull(detected)) }
            failure != null -> postIfCurrent(activeGeneration) { onError(requireNotNull(failure)) }
        }
    }

    private fun getOrCreateSpotter(): KeywordSpotter = synchronized(lock) {
        spotter ?: KeywordSpotter(
            assetManager = appContext.assets,
            config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$MODEL_DIRECTORY/encoder.int8.onnx",
                        decoder = "$MODEL_DIRECTORY/decoder.int8.onnx",
                        joiner = "$MODEL_DIRECTORY/joiner.int8.onnx",
                    ),
                    tokens = "$MODEL_DIRECTORY/tokens.txt",
                    modelType = "zipformer2",
                    numThreads = 1,
                ),
                keywordsFile = "$MODEL_DIRECTORY/keywords.txt",
                keywordsScore = 1.5f,
                keywordsThreshold = 0.25f,
                numTrailingBlanks = 2,
            ),
        ).also { spotter = it }
    }

    private fun clearJob(activeGeneration: Long) = synchronized(lock) {
        if (generation.get() == activeGeneration) job = null
    }

    private fun postIfCurrent(activeGeneration: Long, action: () -> Unit) {
        mainHandler.post {
            if (generation.get() == activeGeneration) action()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 100
        const val MODEL_DIRECTORY = "kws-model"
    }
}
