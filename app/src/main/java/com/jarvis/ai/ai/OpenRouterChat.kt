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
 * Free models through OpenRouter (one key, many models). Used as a backup when Gemini fails.
 * Blocking call: run it on an IO thread.
 */
object OpenRouterChat {
    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    /**
     * Free models, best for code first. OpenRouter changes its free list from time to time,
     * so a model that fails is skipped and the next one is tried.
     */
    val FREE_MODELS = listOf(
        "qwen/qwen3-coder:free",
        "poolside/laguna-xs.2:free",
        "nvidia/nemotron-3-ultra-550b-a55b:free"
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /** Tries each free model in order and returns the first good answer. Throws when none works. */
    fun complete(apiKey: String, system: String, user: String, maxTokens: Int): String {
        var lastError: Exception? = null
        for (model in FREE_MODELS) {
            try {
                return once(apiKey, model, system, user, maxTokens)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("no free OpenRouter model answered")
    }

    private fun once(apiKey: String, model: String, system: String, user: String, maxTokens: Int): String {
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
            .header("HTTP-Referer", "https://github.com/amleshbela111-cyber/Jarvis-Ai")
            .header("X-Title", "Jarvis")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("OpenRouter ${response.code} ($model): ${text.take(120)}")
            return JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }
    }
}
