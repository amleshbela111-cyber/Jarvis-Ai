package com.jarvis.ai.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini text-to-speech: a much more natural voice than the phone's built-in one.
 * If it is not available (no key, no internet, or an error), the caller falls back to the built-in voice.
 */
object NaturalVoice {

    /** Returns the Gemini key, or null when none is saved. Set once from JarvisCore. */
    @Volatile
    var apiKeyProvider: (() -> String?)? = null

    /** Tried in order. The first one that works is used. */
    private val MODELS = listOf("gemini-3.8-flash-tts", "gemini-2.5-flash-preview-tts")
    private const val VOICE = "Charon"
    private const val SAMPLE_RATE = 24000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var track: AudioTrack? = null

    /** Why the last natural-voice attempt failed. Shown on screen. */
    @Volatile
    var lastError: String = ""
        private set

    /** Time (ms) until which Gemini voice is paused after a rate limit. Avoids repeating the same error. */
    @Volatile
    private var cooldownUntil = 0L

    fun canUse(): Boolean =
        System.currentTimeMillis() >= cooldownUntil && apiKeyProvider?.invoke()?.isNotBlank() == true

    /** Speaks [text] in the background. Calls [onFail] when it cannot, so the caller can use the built-in voice. */
    fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit, onFail: () -> Unit) {
        val key = apiKeyProvider?.invoke()
        if (key.isNullOrBlank()) {
            onFail()
            return
        }
        stop()
        job = scope.launch {
            try {
                onStart()
                // Each sentence is made in parallel, and played as soon as it is ready. Speech starts early.
                // One part is made ahead while the current part plays, so at most two requests run at once.
                // Many parallel requests at once hit the rate limit (HTTP 429).
                coroutineScope {
                    val parts = splitSentences(text)
                    var next: Deferred<ByteArray>? = async { synthesizeWithFallback(key, parts[0]) }
                    for (i in parts.indices) {
                        val current = next?.await() ?: break
                        next = if (i + 1 < parts.size) {
                            async { synthesizeWithFallback(key, parts[i + 1]) }
                        } else {
                            null
                        }
                        play(current)
                    }
                }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: "unknown"
                // A rate limit usually means the key's quota is used up for now. Pause Gemini voice for a while.
                if (lastError.contains("429")) cooldownUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                onFail()
            }
        }
    }

    /** Splits a reply into up to six sentence-sized parts. Very short pieces are joined to the next one. */
    private fun splitSentences(text: String): List<String> {
        val parts = text.split(Regex("(?<=[.!?।])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val merged = mutableListOf<String>()
        var buffer = ""
        for (part in parts) {
            buffer = if (buffer.isEmpty()) part else "$buffer $part"
            if (buffer.length >= MIN_PART_CHARS) {
                merged += buffer
                buffer = ""
            }
        }
        if (buffer.isNotEmpty()) merged += buffer
        if (merged.isEmpty()) return listOf(text)
        return if (merged.size <= MAX_PARTS) merged else {
            merged.take(MAX_PARTS - 1) + merged.drop(MAX_PARTS - 1).joinToString(" ")
        }
    }

    private const val MIN_PART_CHARS = 25
    private const val MAX_PARTS = 6
    private const val RATE_LIMIT_ATTEMPTS = 3
    private const val RATE_LIMIT_WAIT_MS = 2_000L
    private const val RATE_LIMIT_COOLDOWN_MS = 10 * 60 * 1000L

    fun stop() {
        job?.cancel()
        job = null
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }

    private fun synthesizeWithFallback(apiKey: String, text: String): ByteArray {
        var failure: Exception? = null
        for (model in MODELS) {
            // A rate-limit (429) is temporary: wait a little and try again before giving up.
            for (attempt in 1..RATE_LIMIT_ATTEMPTS) {
                try {
                    return synthesize(apiKey, text, model)
                } catch (e: Exception) {
                    failure = e
                    val rateLimited = e.message?.contains("429") == true
                    if (!rateLimited) break
                    if (attempt < RATE_LIMIT_ATTEMPTS) Thread.sleep(RATE_LIMIT_WAIT_MS * attempt)
                }
            }
        }
        throw failure ?: IllegalStateException("no TTS model")
    }

    private fun synthesize(apiKey: String, text: String, model: String): ByteArray {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))))
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE))
                        )
                    )
            )
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("TTS error $code")
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(response)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getJSONObject("inlineData").getString("data")
            return Base64.decode(data, Base64.DEFAULT)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun play(pcm: ByteArray) {
        val audio = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = audio
        try {
            audio.write(pcm, 0, pcm.size)
            audio.play()
            // Wait for the sentence to finish playing (16-bit mono = 2 bytes per sample).
            delay(pcm.size * 1000L / (SAMPLE_RATE * 2) + 200L)
        } finally {
            runCatching { audio.stop() }
            runCatching { audio.release() }
            if (track === audio) track = null
        }
    }
}
