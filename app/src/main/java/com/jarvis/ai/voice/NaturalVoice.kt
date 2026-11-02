package com.jarvis.ai.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    private const val VOICE = "Kore"
    private const val SAMPLE_RATE = 24000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var track: AudioTrack? = null

    /** Why the last natural-voice attempt failed. Shown on screen. */
    @Volatile
    var lastError: String = ""
        private set

    fun canUse(): Boolean = apiKeyProvider?.invoke()?.isNotBlank() == true

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
                val pcm = synthesizeWithFallback(key, text)
                onStart()
                play(pcm)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: "unknown"
                onFail()
            }
        }
    }

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
            try {
                return synthesize(apiKey, text, model)
            } catch (e: Exception) {
                failure = e
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
