package com.jarvis.ai.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        maxTokens: Int = MAX_OUTPUT_TOKENS,
        extraImages: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        // First the chosen model, then the fallback. Each gets a second try when the failure is temporary.
        for (candidate in listOf(model, FALLBACK_MODEL).distinct()) {
            for (attempt in 1..2) {
                try {
                    return@withContext callOnce(
                        apiKey, candidate, systemPrompt, userPrompt, imageBase64, expectJson, maxTokens,
                        readMs(maxTokens, candidate), extraImages
                    )
                } catch (e: IOException) {
                    lastError = e
                    if (!isTemporary(e)) break
                    if (attempt == 1) delay(RETRY_WAIT_MS)
                }
            }
        }
        throw lastError ?: IOException("Gemini did not answer")
    }

    /**
     * Asks Gemini's image model for a picture. Returns the picture as base64 (PNG or JPEG), or null when
     * the model cannot make one. Used as the reference picture before the 3D code is written.
     */
    suspend fun generateImage(apiKey: String, prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            val connection = (URL("$BASE_URL/$IMAGE_MODEL:generateContent").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = LONG_READ_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            try {
                val body = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    ))
                    put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
                }.toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) return@withContext null
                val parts = JSONObject(text).optJSONArray("candidates")
                    ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?: return@withContext null
                for (i in 0 until parts.length()) {
                    val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    val data = inline.optString("data")
                    if (data.isNotEmpty()) return@withContext data
                }
                null
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Timeouts, busy servers (429, 500, 502, 503, 504) and dropped connections are worth one more try. */
    private fun isTemporary(e: IOException): Boolean {
        if (e is java.net.SocketTimeoutException || e is java.net.ConnectException) return true
        val text = e.message.orEmpty()
        return TEMPORARY_CODES.any { text.startsWith("Gemini error $it") }
    }

    /** Large outputs (whole app builds) need more time to arrive than a short voice reply. */
    private fun readMs(maxTokens: Int, candidate: String): Int = when {
        maxTokens > MAX_OUTPUT_TOKENS -> LONG_READ_MS
        candidate == FALLBACK_MODEL -> FALLBACK_READ_MS
        else -> PRIMARY_READ_MS
    }

    private fun callOnce(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imageBase64: String?,
        expectJson: Boolean,
        maxTokens: Int,
        readTimeoutMs: Int,
        extraImages: List<String>
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
            val body = buildBody(model, systemPrompt, userPrompt, imageBase64, expectJson, maxTokens, extraImages)
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
        maxTokens: Int,
        extraImages: List<String>
    ): String {
        val parts = JSONArray().put(JSONObject().put("text", userPrompt))
        val images = listOfNotNull(imageBase64) + extraImages
        for (img in images) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", img)
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
        // Model that can draw pictures. If the key has no access to it, the walkthrough falls back to text only.
        const val IMAGE_MODEL = "gemini-2.5-flash-image"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val PRIMARY_READ_MS = 15_000
        const val FALLBACK_READ_MS = 15_000
        const val LONG_READ_MS = 90_000
        const val RETRY_WAIT_MS = 1_200L
        const val MAX_OUTPUT_TOKENS = 2048
        val TEMPORARY_CODES = listOf(429, 500, 502, 503, 504)
    }
}
