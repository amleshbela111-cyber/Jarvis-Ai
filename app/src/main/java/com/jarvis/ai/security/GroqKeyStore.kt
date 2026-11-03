package com.jarvis.ai.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores the Groq API key (used for Whisper speech-to-text), encrypted on the phone. Reads never throw. */
class GroqKeyStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(key: String) {
        prefs.edit().putString(KEY_NAME, key).apply()
    }

    fun load(): String? = runCatching { prefs.getString(KEY_NAME, null) }.getOrNull()

    fun isSet(): Boolean = !load().isNullOrBlank()

    fun clear() {
        prefs.edit().remove(KEY_NAME).apply()
    }

    private companion object {
        const val FILE_NAME = "jarvis_groq_prefs"
        const val KEY_NAME = "groq_api_key"
    }
}
