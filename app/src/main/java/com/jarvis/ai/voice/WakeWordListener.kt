package com.jarvis.ai.voice

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Listens offline with Vosk for the wake phrase "Hey Jarvis". It does not use Google's recognizer,
 * so no start/stop beep is played while Jarvis is waiting. Call [start] and [stop] on the main thread.
 */
class WakeWordListener(
    private val context: Context,
    private val onWake: () -> Unit
) : RecognitionListener {

    private var service: SpeechService? = null
    private var fired = false

    /** Loads the model in the background, so the first "Hey Jarvis" check does not freeze the screen. */
    fun preload() {
        if (cachedModel != null || !VoskModelStore.isReady(context, "en")) return
        Thread {
            cachedModel = runCatching { Model(VoskModelStore.dir(context, "en").absolutePath) }.getOrNull()
        }.start()
    }

    /** Loads the model if needed and starts listening. Returns false when no usable model is saved. */
    fun start(): Boolean {
        if (service != null) return true
        if (!VoskModelStore.isReady(context, "en")) return false
        val model = cachedModel ?: runCatching {
            Model(VoskModelStore.dir(context, "en").absolutePath)
        }.getOrNull() ?: return false
        cachedModel = model
        val recognizer = Recognizer(model, SAMPLE_RATE, GRAMMAR)
        val speech = runCatching { SpeechService(recognizer, SAMPLE_RATE) }.getOrNull() ?: return false
        fired = false
        speech.startListening(this)
        service = speech
        return true
    }

    fun stop() {
        service?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        service = null
    }

    override fun onPartialResult(hypothesis: String?) = check(textOf(hypothesis, "partial"))

    override fun onResult(hypothesis: String?) = check(textOf(hypothesis, "text"))

    override fun onFinalResult(hypothesis: String?) = check(textOf(hypothesis, "text"))

    override fun onError(exception: Exception?) {
        // Errors are handled by ListeningService, which restarts the listener when needed.
    }

    override fun onTimeout() = Unit

    private fun check(text: String) {
        if (fired || !WAKE_PHRASE.containsMatchIn(text)) return
        fired = true
        onWake()
    }

    private fun textOf(hypothesis: String?, key: String): String =
        runCatching { JSONObject(hypothesis.orEmpty()).optString(key) }.getOrDefault("")

    private companion object {
        const val SAMPLE_RATE = 16000f
        // Only these phrases are expected. "[unk]" lets everything else be ignored.
        const val GRAMMAR = "[\"hey jarvis\", \"hi jarvis\", \"hello jarvis\", \"[unk]\"]"

        val WAKE_PHRASE = Regex("(hey|hi|hello)\\s+jarvis", RegexOption.IGNORE_CASE)

        @Volatile
        var cachedModel: Model? = null
    }
}
