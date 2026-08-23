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

    companion object {
        const val MODEL = "gpt-4o-mini-tts"
        private const val SPEECH_URL = "https://api.openai.com/v1/audio/speech"

        val VOICES = listOf(
            "alloy", "ash", "ballad", "cedar", "coral", "echo", "fable",
            "marin", "nova", "onyx", "sage", "shimmer", "verse",
        )
    }
}
