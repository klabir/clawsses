package com.clawsses.phone.tts

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs API client for text-to-speech synthesis.
 */
class ElevenLabsClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Fetch available voices from ElevenLabs API.
     */
    suspend fun getVoices(apiKey: String): Result<List<Voice>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/voices")
                .header("xi-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Failed to fetch voices (HTTP ${response.code})")
                    )
                }
                val body = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Empty response body"))
                Result.success(gson.fromJson(body, VoicesResponse::class.java).voices)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Synthesize text to speech and return MP3 bytes.
     */
    suspend fun synthesize(
        apiKey: String,
        voiceId: String,
        text: String,
        speed: Double = 1.0
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val voiceSettings = if (speed != 1.0) VoiceSettings(speed = speed) else null
            val requestBody = SynthesisRequest(
                text = text,
                modelId = MODEL_ID,
                voiceSettings = voiceSettings
            )

            val request = Request.Builder()
                .url("$BASE_URL/text-to-speech/$voiceId/stream")
                .header("xi-api-key", apiKey)
                .header("Content-Type", "application/json")
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("TTS synthesis failed (HTTP ${response.code})")
                    )
                }
                val bytes = response.body?.bytes()
                    ?: return@withContext Result.failure(Exception("Empty response body"))
                Result.success(bytes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val BASE_URL = "https://api.elevenlabs.io/v1"
        private const val MODEL_ID = "eleven_turbo_v2_5"
    }
}

// API response models

data class Voice(
    @SerializedName("voice_id") val voiceId: String,
    @SerializedName("name") val name: String,
    @SerializedName("preview_url") val previewUrl: String? = null,
    @SerializedName("category") val category: String? = null
)

data class VoicesResponse(
    @SerializedName("voices") val voices: List<Voice>
)

data class VoiceSettings(
    @SerializedName("speed") val speed: Double
)

data class SynthesisRequest(
    @SerializedName("text") val text: String,
    @SerializedName("model_id") val modelId: String,
    @SerializedName("voice_settings") val voiceSettings: VoiceSettings? = null
)
