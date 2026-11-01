package com.jarvis.ai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.ai.model.SpeechLanguage
import java.util.Locale

/**
 * Wraps Android's SpeechRecognizer (speech -> text) and TextToSpeech (text -> speech).
 *
 * All methods must be called on the main thread. Callbacks are delivered on the main thread.
 */
class VoiceController(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onListeningStarted()
        fun onSoundLevel(level: Float)
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onListeningStopped()
        fun onVoiceError(message: String)
        fun onSpeechStarted()
        fun onSpeechFinished()
        fun onTtsReadyChanged(ready: Boolean)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var language: SpeechLanguage = SpeechLanguage.ENGLISH_INDIA

    init {
        tts = TextToSpeech(appContext) { status ->
            mainHandler.post {
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) applyTtsLanguage()
                listener.onTtsReadyChanged(ttsReady)
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post { listener.onSpeechStarted() }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post { listener.onSpeechFinished() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post { listener.onSpeechFinished() }
            }
        })
    }

    fun setLanguage(lang: SpeechLanguage) {
        language = lang
        if (ttsReady) applyTtsLanguage()
    }

    /** Starts one recognition session. Results arrive through [Listener]. */
    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listener.onVoiceError("Speech recognition is not available on this device.")
            return
        }
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(recognitionListener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        rec.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    /** Speaks [text]. Returns false if TTS is not ready, so the caller can recover. */
    fun speak(text: String): Boolean {
        val engine = tts ?: return false
        if (!ttsReady) return false
        val utteranceId = "jarvis-${System.nanoTime()}"
        return engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun applyTtsLanguage() {
        val engine = tts ?: return
        val result = engine.setLanguage(Locale.forLanguageTag(language.tag))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener.onListeningStarted()
        }

        override fun onBeginningOfSpeech() {
            // Nothing to do; the UI already shows LISTENING from onReadyForSpeech.
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Typical RMS range is about -2 to 10 dB. Normalize to 0..1 for the orb.
            listener.onSoundLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Raw audio buffers are not needed; the recognizer handles them.
        }

        override fun onEndOfSpeech() {
            listener.onListeningStopped()
        }

        override fun onError(error: Int) {
            listener.onListeningStopped()
            listener.onVoiceError(errorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                listener.onFinalResult(text)
            } else {
                listener.onVoiceError("Didn't catch that. Try again.")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) listener.onPartialResult(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // Reserved events; not used in Stage 1.
        }
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> "Speech service network error."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Try again in a moment."
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error."
        SpeechRecognizer.ERROR_CLIENT -> "Speech client error."
        else -> "Speech recognition error (code $error)."
    }
}
