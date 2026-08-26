package com.clawsses.phone.tts

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Minimal OpenAI Speech API client. Error bodies are never retained or logged. */
class OpenAiTtsClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun synthesize(
        apiKey: String,
        voice: String,
        text: String,
        speed: Double = 1.0,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JsonObject().apply {
                addProperty("model", MODEL)
                addProperty("voice", voice)
                addProperty("input", text)
                addProperty("response_format", "mp3")
                addProperty("speed", speed)
            }
            val request = Request.Builder()
                .url(SPEECH_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("OpenAI TTS failed (HTTP ${response.code})")
                }
                response.body?.bytes() ?: throw IllegalStateException("Empty OpenAI TTS response")
            }
        }
    }

    /**
     * Streams raw 24 kHz, signed 16-bit, little-endian mono PCM as it arrives.
     * [onAudio] must consume the supplied bytes before returning because the buffer is reused.
     */
    suspend fun streamPcm(
        apiKey: String,
        voice: String,
        text: String,
        speed: Double = 1.0,
        onAudio: (buffer: ByteArray, byteCount: Int) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                speechRequest(
                    apiKey = apiKey,
                    voice = voice,
                    text = text,
                    speed = speed,
                    responseFormat = "pcm",
                    streamFormat = "audio",
                )
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("OpenAI TTS failed (HTTP ${response.code})")
                }
                val input = response.body?.byteStream()
                    ?: throw IllegalStateException("Empty OpenAI TTS response")
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                while (true) {
                    val byteCount = input.read(buffer)
                    if (byteCount < 0) break
                    if (byteCount > 0) onAudio(buffer, byteCount)
                }
            }
        }
    }

    /** Downloads a follow-up PCM segment while the preceding segment is playing. */
    suspend fun synthesizePcm(
        apiKey: String,
        voice: String,
        text: String,
        speed: Double = 1.0,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                speechRequest(
                    apiKey = apiKey,
                    voice = voice,
                    text = text,
                    speed = speed,
                    responseFormat = "pcm",
                    streamFormat = "audio",
                )
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("OpenAI TTS failed (HTTP ${response.code})")
                }
                response.body?.bytes() ?: throw IllegalStateException("Empty OpenAI TTS response")
            }
        }
    }

    private fun speechRequest(
        apiKey: String,
        voice: String,
        text: String,
        speed: Double,
        responseFormat: String,
        streamFormat: String? = null,
    ): Request {
        val body = JsonObject().apply {
            addProperty("model", MODEL)
            addProperty("voice", voice)
            addProperty("input", text)
            addProperty("response_format", responseFormat)
            addProperty("speed", speed)
            streamFormat?.let { addProperty("stream_format", it) }
        }
        return Request.Builder()
            .url(SPEECH_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    companion object {
        const val MODEL = "gpt-4o-mini-tts"
        private const val SPEECH_URL = "https://api.openai.com/v1/audio/speech"
        private const val STREAM_BUFFER_BYTES = 8 * 1024

        val VOICES = listOf(
            "alloy", "ash", "ballad", "cedar", "coral", "echo", "fable",
            "marin", "nova", "onyx", "sage", "shimmer", "verse",
        )
    }
}
