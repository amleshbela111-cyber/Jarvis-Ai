package com.jarvis.ai.live

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Gemini Live session over a WebSocket. The mic streams 16 kHz PCM. Only speech frames (plus a short
 * lead-in and tail) are sent, so silence does not use quota. Reply audio is played at 24 kHz, and
 * playback stops when the server reports an interruption (when the user starts talking over Jarvis).
 */
class LiveVoice(
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    /** Returns the newest camera frame (base64 JPEG), or null. Sent about once a second while set. */
    private val videoFrame: (() -> String?)?,
    private val onState: (String) -> Unit
) {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var micThread: Thread? = null
    private var playExecutor: ExecutorService? = null
    private var frameThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        playExecutor = Executors.newSingleThreadExecutor()
        onState("Live se jud raha hoon...")
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject().put(
                    "setup",
                    JSONObject()
                        .put("model", "models/$model")
                        .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
                        .put(
                            "systemInstruction",
                            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                        )
                )
                webSocket.send(setup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onState("Live band hua: ${t.message?.take(60) ?: "network"}")
                stop()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stop()
            }
        })
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { socket?.close(1000, null) }
        socket = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        micThread = null
        frameThread?.interrupt()
        frameThread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        playExecutor?.shutdownNow()
        playExecutor = null
        onState("Live band hai.")
    }

    private fun handleMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (json.has("setupComplete")) {
            startMic()
            startFrames()
            onState("Sun raha hoon...")
            return
        }
        val content = json.optJSONObject("serverContent") ?: return
        if (content.optBoolean("interrupted", false)) flushPlayback()
        val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val pcm = runCatching { Base64.decode(inline.optString("data"), Base64.DEFAULT) }.getOrNull() ?: continue
            playPcm(pcm)
        }
    }

    // ---------- Playback ----------

    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val minBuf = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf * 2, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        return t
    }

    private fun playPcm(pcm: ByteArray) {
        playExecutor?.execute { runCatching { ensureTrack().write(pcm, 0, pcm.size) } }
    }

    private fun flushPlayback() {
        playExecutor?.execute {
            runCatching {
                track?.pause()
                track?.flush()
                track?.play()
            }
        }
    }

    // ---------- Mic and VAD ----------

    @SuppressLint("MissingPermission")
    private fun startMic() {
        val minBuf = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            IN_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf * 2, FRAME_BYTES * 4)
        )
        recorder = rec
        rec.startRecording()
        micThread = Thread {
            val frame = ByteArray(FRAME_BYTES)
            val preroll = ArrayDeque<ByteArray>()
            var tail = 0
            var speaking = false
            while (running && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) continue
                val chunk = frame.copyOf(n)
                val loud = rms(chunk) > SPEECH_RMS
                if (loud) {
                    if (!speaking) {
                        speaking = true
                        preroll.forEach { sendAudio(it) }
                        preroll.clear()
                    }
                    tail = TAIL_FRAMES
                    sendAudio(chunk)
                } else if (speaking) {
                    sendAudio(chunk)
                    tail--
                    if (tail <= 0) {
                        speaking = false
                        sendStreamEnd()
                    }
                } else {
                    preroll.addLast(chunk)
                    if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                }
            }
        }.also { it.start() }
    }

    private fun sendAudio(pcm: ByteArray) {
        val data = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val msg = JSONObject().put(
            "realtimeInput",
            JSONObject().put("audio", JSONObject().put("data", data).put("mimeType", "audio/pcm;rate=$IN_RATE"))
        )
        socket?.send(msg.toString())
    }

    // ---------- Camera frames ----------

    /** Sends the newest camera frame about once a second, but only when it has changed. Live then sees what is in view. */
    private fun startFrames() {
        val source = videoFrame ?: return
        frameThread = Thread {
            var lastSent: String? = null
            while (running) {
                val jpeg = runCatching { source() }.getOrNull()
                if (jpeg != null && jpeg !== lastSent) {
                    lastSent = jpeg
                    socket?.send(
                        JSONObject().put(
                            "realtimeInput",
                            JSONObject().put("video", JSONObject().put("data", jpeg).put("mimeType", "image/jpeg"))
                        ).toString()
                    )
                }
                try {
                    Thread.sleep(FRAME_EVERY_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.start() }
    }

    private fun sendStreamEnd() {
        socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
    }

    private fun rms(b: ByteArray): Double {
        val n = b.size / 2
        var sum = 0.0
        for (i in 0 until n) {
            val s = ((b[2 * i + 1].toInt() shl 8) or (b[2 * i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
        }
        return sqrt(sum / max(n, 1))
    }

    companion object {
        const val IN_RATE = 16000
        const val OUT_RATE = 24000
        // 20 ms frames at 16 kHz, 16-bit mono
        const val FRAME_BYTES = 640
        const val SPEECH_RMS = 600.0
        const val PREROLL_FRAMES = 15   // 300 ms kept before speech starts
        const val TAIL_FRAMES = 25      // 500 ms of quiet sent after speech before stopping
        const val FRAME_EVERY_MS = 1000L // camera frames per second sent to Live
    }
}
