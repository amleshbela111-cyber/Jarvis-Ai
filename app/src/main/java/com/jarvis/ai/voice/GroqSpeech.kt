package com.jarvis.ai.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speech-to-text with Groq's Whisper model. Jarvis records one sentence itself, stops when the speaker is
 * quiet, and sends the audio once. This is much more accurate for Hindi-English mixed speech than the phone's
 * built-in recognizer, and it does not play the system's start/stop beep.
 *
 * [recordUtterance] blocks, so call it from a background thread only.
 */
object GroqSpeech {

    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3"
    private const val RATE = 16000
    private const val LOUD_PEAK = 1500

    /** Records until the speaker has been quiet for [silenceMs], or [maxMs] has passed. Null when nothing was said. */
    fun recordUtterance(maxMs: Long = 12_000L, silenceMs: Long = 1_200L): ByteArray? {
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, 4096)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        val pcm = ByteArrayOutputStream()
        val buffer = ShortArray(1024)
        var heardVoice = false
        var lastVoice = 0L
        val start = System.currentTimeMillis()
        try {
            recorder.startRecording()
            while (System.currentTimeMillis() - start < maxMs) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) break
                var peak = 0
                for (i in 0 until read) {
                    val v = buffer[i].toInt()
                    pcm.write(v and 0xFF)
                    pcm.write((v shr 8) and 0xFF)
                    val a = if (v < 0) -v else v
                    if (a > peak) peak = a
                }
                val now = System.currentTimeMillis()
                if (peak > LOUD_PEAK) {
                    heardVoice = true
                    lastVoice = now
                }
                if (heardVoice && now - lastVoice > silenceMs) break
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        return if (heardVoice) pcm.toByteArray() else null
    }

    /** Sends the audio to Groq and returns the recognized text. Throws IOException with a short message on failure. */
    fun transcribe(apiKey: String, pcm: ByteArray): String {
        val boundary = "jarvis" + System.nanoTime()
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.use { out ->
                fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                text("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$MODEL\r\n")
                text("--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n")
                text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n")
                text("Content-Type: audio/wav\r\n\r\n")
                out.write(wavHeader(pcm.size))
                out.write(pcm)
                text("\r\n--$boundary--\r\n")
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Groq $code: ${body.take(120)}")
            return JSONObject(body).optString("text").trim()
        } finally {
            connection.disconnect()
        }
    }

    /** A 44-byte WAV header for 16 kHz mono 16-bit audio. */
    private fun wavHeader(dataSize: Int): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = RATE * channels * bits / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(RATE)
            putInt(byteRate)
            putShort((channels * bits / 8).toShort())
            putShort(bits.toShort())
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
    }
}
