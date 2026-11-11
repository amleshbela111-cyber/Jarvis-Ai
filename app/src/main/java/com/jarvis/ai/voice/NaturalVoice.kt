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
                // First choice: one streaming request for the whole reply. Sound starts with the first audio chunk.
                var played = false
                val streamed = try {
                    streamAll(key, text) { played = true }
                    true
                } catch (e: Exception) {
                    // Nothing was played yet: use the sentence-by-sentence path below instead.
                    if (played) throw e
                    false
                }
                if (!streamed) {
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

    /**
     * Fades the last 80 ms of a sentence to silence and adds 60 ms of silence after it.
     * This stops the hard cut at the end, which is what makes a hiss or a click.
     */
    private fun withFadeOut(raw: ByteArray): ByteArray {
        val samples = raw.size / 2
        val fadeSamples = minOf(samples, SAMPLE_RATE * 80 / 1000)
        val tailSamples = SAMPLE_RATE * 60 / 1000
        val out = ByteArray(raw.size + tailSamples * 2)
        System.arraycopy(raw, 0, out, 0, raw.size)
        val start = samples - fadeSamples
        for (i in 0 until fadeSamples) {
            val gain = 1f - (i + 1f) / fadeSamples
            val idx = (start + i) * 2
            val s = ((out[idx].toInt() and 0xff) or (out[idx + 1].toInt() shl 8)).toShort()
            val v = (s * gain).toInt()
            out[idx] = (v and 0xff).toByte()
            out[idx + 1] = (v shr 8).toByte()
        }
        return out
    }

    private const val STREAM_MODEL = "gemini-3.1-flash-tts-preview"
    /** The last 80 ms are held back while streaming, so the end can be faded out once the stream finishes. */
    private const val TAIL_BYTES = SAMPLE_RATE * 80 / 1000 * 2

    /**
     * Plays the whole [text] from one streaming request. Each audio chunk is written to the track as it arrives.
     * Throws when no audio arrives, so the caller can fall back to the sentence path.
     */
    private fun streamAll(apiKey: String, text: String, markPlayed: () -> Unit) {
        val bufBytes = maxOf(
            AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
            SAMPLE_RATE / 2 * 2 // 0.5 second
        )
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
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audio
        try {
            audio.play()
            var tail = ByteArray(0)
            var gotAudio = false
            streamRequest(apiKey, text) { chunk ->
                gotAudio = true
                markPlayed()
                val all = tail + chunk
                var writeLen = all.size - minOf(TAIL_BYTES, all.size)
                writeLen -= writeLen % 2 // whole 16-bit samples only
                if (writeLen > 0) writeAll(audio, all, writeLen)
                tail = all.copyOfRange(writeLen, all.size)
            }
            if (!gotAudio) throw IllegalStateException("no audio in stream")
            val last = tail.copyOf(tail.size - tail.size % 2)
            val ending = withFadeOut(last)
            writeAll(audio, ending, ending.size)
            // The track still has up to one buffer of sound to play. Wait for it, then release.
            Thread.sleep(bufBytes * 1000L / (SAMPLE_RATE * 2) + 200L)
        } finally {
            runCatching { audio.stop() }
            runCatching { audio.flush() }
            runCatching { audio.release() }
            if (track === audio) track = null
        }
    }

    /** Writes [len] bytes. Returns quietly when the track was stopped by [stop]. */
    private fun writeAll(audio: AudioTrack, data: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = audio.write(data, off, len - off)
            if (n <= 0) return
            off += n
        }
    }

    private fun ttsBody(text: String): JSONObject = JSONObject()
        .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
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

    /** Reads the server-sent events of one streaming TTS request and passes each audio chunk on. */
    private fun streamRequest(apiKey: String, text: String, onAudio: (ByteArray) -> Unit) {
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/$STREAM_MODEL:streamGenerateContent?alt=sse")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            connection.outputStream.use { it.write(ttsBody(text).toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("TTS error $code")
            connection.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val json = line.removePrefix("data:").trim()
                    if (json.isEmpty()) continue
                    val data = JSONObject(json).optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                        ?.optJSONObject("inlineData")?.optString("data").orEmpty()
                    if (data.isNotEmpty()) onAudio(Base64.decode(data, Base64.DEFAULT))
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun play(raw: ByteArray) {
        val pcm = withFadeOut(raw)
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
            runCatching { audio.flush() }
            runCatching { audio.release() }
            if (track === audio) track = null
        }
    }
}
