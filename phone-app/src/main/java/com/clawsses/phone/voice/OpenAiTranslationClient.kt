package com.clawsses.phone.voice

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class OpenAiTranslationClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun translate(apiKey: String, text: String, targetLanguage: String): Result<String> {
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("OpenAI API key required for translation"))
        if (text.isBlank()) return Result.success("")

        val payload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put(
                "instructions",
                "Translate the input into $targetLanguage. Return only the translation, without commentary.",
            )
            put("input", text)
        }
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(IOException("Translation request failed (${it.code})")))
                            }
                            return
                        }
                        val translated = runCatching { parseOutputText(body) }
                        if (continuation.isActive) continuation.resume(translated)
                    }
                }
            })
        }
    }

    internal fun parseOutputText(json: String): String {
        val root = JSONObject(json)
        val output = root.optJSONArray("output") ?: error("Translation response had no output")
        for (outputIndex in 0 until output.length()) {
            val content = output.optJSONObject(outputIndex)?.optJSONArray("content") ?: continue
            for (contentIndex in 0 until content.length()) {
                val item = content.optJSONObject(contentIndex) ?: continue
                if (item.optString("type") == "output_text") {
                    return item.optString("text").trim().takeIf(String::isNotEmpty)
                        ?: error("Translation response was empty")
                }
            }
        }
        error("Translation response had no text")
    }
}
