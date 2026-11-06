package com.jarvis.ai.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.ai.core.JarvisCore
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
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var beepMuted = false
    private val unmuteBeep = Runnable { setBeepMuted(false) }

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
            // Auto-detect Hindi or English when the phone supports it (Android 14+). Older phones keep the chosen language.
            runCatching {
                val enable = RecognizerIntent::class.java.getField("EXTRA_ENABLE_LANGUAGE_DETECTION").get(null) as String
                val allowed = RecognizerIntent::class.java.getField("EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES").get(null) as String
                putExtra(enable, true)
                putExtra(allowed, arrayListOf("en-IN", "hi-IN"))
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // Wait longer in silence before ending a session, so sessions last and do not cut off mid-sentence.
            // Short silence ends the sentence quickly, so the prompt is sent right after you stop speaking.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        }
        muteBeepBriefly()
        rec.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    /** Speaks [text] with the natural Gemini voice, or the phone's voice if that is unavailable. */
    fun speak(text: String): Boolean {
        // Set the phone voice's language from the current setting every time, so the chosen language is used.
        runCatching { language = JarvisCore.settings().speechLanguage }
        if (ttsReady) applyTtsLanguage()
        // The phone's built-in voice is the default. Gemini's voice is used only when turned on in Settings.
        val useGemini = runCatching { JarvisCore.settings().useGeminiVoice }.getOrDefault(false)
        if (useGemini && NaturalVoice.canUse()) {
            NaturalVoice.speak(
                text,
                onStart = { mainHandler.post { listener.onSpeechStarted() } },
                onDone = { mainHandler.post { listener.onSpeechFinished() } },
                onFail = {
                    mainHandler.post {
                        listener.onVoiceError("Gemini awaaz nahi mili (${NaturalVoice.lastError.take(80)}). Phone ki awaaz use ki.")
                        speakWithPhoneVoice(text)
                    }
                }
            )
            return true
        }
        return speakWithPhoneVoice(text)
    }

    private fun speakWithPhoneVoice(text: String): Boolean {
        val engine = tts ?: return false
        if (!ttsReady) return false
        val utteranceId = "jarvis-${System.nanoTime()}"
        return engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
    }

    fun stopSpeaking() {
        NaturalVoice.stop()
        tts?.stop()
    }

    fun release() {
        NaturalVoice.stop()
        setBeepMuted(false)
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun applyTtsLanguage() {
        val engine = tts ?: return
        val locale = Locale.forLanguageTag(language.tag)
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        // A slightly slower pace and lower pitch sound more like a calm person speaking.
        engine.setSpeechRate(
            when (runCatching { JarvisCore.settings().voiceSpeed }.getOrDefault("normal")) {
                "slow" -> 0.8f
                "fast" -> 1.1f
                else -> 0.95f
            }
        )
        engine.setPitch(0.95f)
        // A voice picked in Settings wins. Otherwise the best installed voice for this language is used.
        val chosen = runCatching { JarvisCore.settings().voiceName(language) }.getOrNull()
        val picked = chosen?.let { name -> engine.voices.orEmpty().firstOrNull { it.name == name } }
        if (picked != null) engine.voice = picked else pickBestVoice(engine, locale)
    }

    /** Chooses the highest-quality installed voice for this language. Falls back silently. */
    private fun pickBestVoice(engine: TextToSpeech, locale: Locale) {
        runCatching {
            val best = engine.voices.orEmpty()
                .filter { it.locale.language == locale.language }
                .maxByOrNull { it.quality }
            if (best != null) engine.voice = best
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            mainHandler.removeCallbacks(unmuteBeep)
            mainHandler.postDelayed(unmuteBeep, POST_READY_UNMUTE_MS)
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
            muteBeepBriefly()
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

    /** Mutes the beep streams from the moment listening starts until shortly after it is ready. */
    private fun muteBeepBriefly() {
        if (!runCatching { JarvisCore.settings().muteBeep }.getOrDefault(true)) return
        setBeepMuted(true)
        mainHandler.removeCallbacks(unmuteBeep)
        mainHandler.postDelayed(unmuteBeep, BEEP_MUTE_MS)
    }

    // The mic start/stop beep plays on the system and notification streams. The media stream is NOT muted here,
    // because muting it silenced YouTube Shorts and other music every time Jarvis started listening.
    private val beepStreams = intArrayOf(
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_RING
    )
    private val mutedByUs = mutableSetOf<Int>()

    private fun setBeepMuted(muted: Boolean) {
        runCatching {
            for (stream in beepStreams) {
                if (muted && !mutedByUs.contains(stream) && !audioManager.isStreamMute(stream)) {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                    mutedByUs.add(stream)
                } else if (!muted && mutedByUs.remove(stream)) {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                }
            }
        }
        beepMuted = muted
    }

    private companion object {
        const val BEEP_MUTE_MS = 1500L
        const val POST_READY_UNMUTE_MS = 300L
        const val SHORT_REPLY_CHARS = 90
    }
}
