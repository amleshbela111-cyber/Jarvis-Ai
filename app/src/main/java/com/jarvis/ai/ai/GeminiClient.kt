package com.jarvis.ai.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal Gemini REST client. The key is sent in a header, never in the URL.
 *
 * Speed settings: short replies (maxOutputTokens), and no hidden "thinking" on Gemini 2.5 models.
 * If the chosen model times out or fails at the network level, one retry uses the fast fallback model.
 */
class GeminiClient {

    suspend fun generate(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String? = null,
        expectJson: Boolean = true,
        maxTokens: Int = MAX_OUTPUT_TOKENS
    ): String = withContext(Dispatchers.IO) {
        try {
            callOnce(apiKey, model, systemPrompt, userPrompt, imageBase64, expectJson, maxTokens, primaryReadMs(maxTokens))
        } catch (e: IOException) {
            // Only retry on network timeouts, not on errors such as a retired model (404) or bad key.
            val retryable = e is java.net.SocketTimeoutException || e is java.net.ConnectException
            if (model == FALLBACK_MODEL || !retryable) throw e
            callOnce(apiKey, FALLBACK_MODEL, systemPrompt, userPrompt, imageBase64, expectJson, maxTokens, primaryReadMs(maxTokens))
        }
    }

    /** Large outputs (whole app builds) need more time to arrive than a short voice reply. */
    private fun primaryReadMs(maxTokens: Int): Int =
        if (maxTokens > MAX_OUTPUT_TOKENS) LONG_READ_MS else PRIMARY_READ_MS

    private fun callOnce(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String?,
        expectJson: Boolean,
        maxTokens: Int,
        readTimeoutMs: Int
    ): String {
        val connection = (URL("$BASE_URL/$model:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            val body = buildBody(model, systemPrompt, userPrompt, imageBase64, expectJson, maxTokens)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("Gemini error $code: ${responseText.take(200)}")
            }
            return extractText(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildBody(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String?,
        expectJson: Boolean,
        maxTokens: Int
    ): String {
        val parts = JSONArray().put(JSONObject().put("text", userPrompt))
        if (imageBase64 != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", imageBase64)
                )
            )
        }

        val generationConfig = JSONObject()
            .put("temperature", 0.2)
            .put("maxOutputTokens", maxTokens)
        if (expectJson) generationConfig.put("responseMimeType", "application/json")
        // Gemini 2.5 models "think" before answering by default, which adds delay. Turn that off for speed.
        if (model.startsWith("gemini-2.5")) {
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }

        return JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            put("generationConfig", generationConfig)
        }.toString()
    }

    private fun extractText(responseBody: String): String {
        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates")
            ?: throw IOException("Gemini returned no answer (the request may have been blocked)")
        val first = candidates.getJSONObject(0)
        if (first.optString("finishReason") == "MAX_TOKENS") {
            throw IOException("Gemini answer was cut off (output limit reached)")
        }
        val parts = first.getJSONObject("content").getJSONArray("parts")
        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            // Skip internal "thought" parts so only the real answer is used.
            if (!part.optBoolean("thought", false)) text.append(part.optString("text"))
        }
        return text.toString()
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val FALLBACK_MODEL = "gemini-3.6-flash"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val PRIMARY_READ_MS = 12_000
        const val FALLBACK_READ_MS = 12_000
        const val LONG_READ_MS = 90_000
        const val MAX_OUTPUT_TOKENS = 2048
    }
}
