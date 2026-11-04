package com.jarvis.ai.settings

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.ai.model.SpeechLanguage

/**
 * Non-secret user preferences. The Gemini API key is NOT stored here;
 * it lives in [com.jarvis.ai.security.ApiKeyStore], which encrypts it.
 */
class JarvisSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var speechLanguage: SpeechLanguage
        get() {
            val tag = prefs.getString(KEY_LANGUAGE, null)
            return SpeechLanguage.values().firstOrNull { it.tag == tag } ?: SpeechLanguage.ENGLISH_INDIA
        }
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, value.tag).apply()
        }

    var speakResponses: Boolean
        get() = prefs.getBoolean(KEY_SPEAK_RESPONSES, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SPEAK_RESPONSES, value).apply()
        }

    /** The phone voice picked for one language (its name as Android lists it), or null for the automatic choice. */
    fun voiceName(language: SpeechLanguage): String? = prefs.getString(KEY_VOICE_PREFIX + language.tag, null)

    fun setVoiceName(language: SpeechLanguage, name: String) {
        prefs.edit().putString(KEY_VOICE_PREFIX + language.tag, name).apply()
    }

    /** False (default) uses the phone's built-in voice for the chosen language. True uses Gemini's voice. */
    var useGeminiVoice: Boolean
        get() = prefs.getBoolean(KEY_GEMINI_VOICE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GEMINI_VOICE, value).apply()
        }

    var geminiModel: String
        get() {
            val saved = prefs.getString(KEY_GEMINI_MODEL, null)
            // Models retired by Google (gemini-2.0-*) are replaced with the current default.
            return if (saved.isNullOrBlank() || saved.startsWith("gemini-2.0")) DEFAULT_MODEL else saved
        }
        set(value) {
            prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()
        }

    /** "short" (1-2 sentences) or "detailed" (up to 5 sentences). */
    var replyStyle: String
        get() = prefs.getString(KEY_REPLY_STYLE, "short") ?: "short"
        set(value) {
            prefs.edit().putString(KEY_REPLY_STYLE, value).apply()
        }

    /** "slow", "normal", or "fast". Applies to the phone's voice. */
    var voiceSpeed: String
        get() = prefs.getString(KEY_VOICE_SPEED, "normal") ?: "normal"
        set(value) {
            prefs.edit().putString(KEY_VOICE_SPEED, value).apply()
        }

    /** True keeps the microphone's start and stop beep silent. */
    var muteBeep: Boolean
        get() = prefs.getBoolean(KEY_MUTE_BEEP, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MUTE_BEEP, value).apply()
        }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite"

        private const val PREFS_NAME = "jarvis_settings"
        private const val KEY_LANGUAGE = "speech_language"
        private const val KEY_SPEAK_RESPONSES = "speak_responses"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_REPLY_STYLE = "reply_style"
        private const val KEY_VOICE_SPEED = "voice_speed"
        private const val KEY_MUTE_BEEP = "mute_beep"
        private const val KEY_GEMINI_VOICE = "use_gemini_voice"
        private const val KEY_VOICE_PREFIX = "phone_voice_"
    }
}
