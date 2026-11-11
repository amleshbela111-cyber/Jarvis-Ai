package com.jarvis.ai.camera

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Describes one camera frame with Groq's vision model. Groq answers much faster than a live session,
 * so a new object is named soon after it comes into view. Blocking call: run it on an IO thread.
 */
object GroqVision {
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    const val MODEL = "qwen/qwen3.8-27b"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Returns one short answer about [jpegBase64] (a base64 JPEG). Throws IOException on failure. */
    fun describe(apiKey: String, jpegBase64: String, prompt: String): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")
                )
            )
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 120)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Groq ${response.code}: ${text.take(120)}")
            return JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }
    }
}
