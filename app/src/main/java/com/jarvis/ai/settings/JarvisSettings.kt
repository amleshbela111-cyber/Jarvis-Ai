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

    var geminiModel: String
        get() = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) {
            prefs.edit().putString(KEY_GEMINI_MODEL, value).apply()
        }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.0-flash-lite"

        private const val PREFS_NAME = "jarvis_settings"
        private const val KEY_LANGUAGE = "speech_language"
        private const val KEY_SPEAK_RESPONSES = "speak_responses"
        private const val KEY_GEMINI_MODEL = "gemini_model"
    }
}
