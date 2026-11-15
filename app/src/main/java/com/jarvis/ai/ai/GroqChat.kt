package com.jarvis.ai.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Text chat on Groq (fast, low cost). Used by the app builder for the plan and for small files.
 * Blocking call: run it on an IO thread. Throws IOException when Groq refuses or answers badly.
 */
object GroqChat {
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    /** Fastest, for the plan (the file list). */
    const val FAST_MODEL = "llama-3.1-8b-instant"
    /** Stronger, for small project files such as Gradle, manifest and XML. */
    const val CODE_MODEL = "llama-3.3-70b-versatile"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun complete(apiKey: String, model: String, system: String, user: String, maxTokens: Int): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.2)
            .put("messages", messages)
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
