package com.jarvis.ai.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the Gemini API key encrypted at rest (AES-256 via the Android Keystore).
 * The key is never written to source files, logs, or the APK.
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val app = context.applicationContext
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(apiKey: String) {
        prefs.edit().putString(KEY_API, apiKey.trim()).apply()
    }

    fun load(): String? = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun hasKey(): Boolean = runCatching { load() != null }.getOrDefault(false)

    fun clear() {
        prefs.edit().remove(KEY_API).apply()
    }

    private companion object {
        const val FILE_NAME = "jarvis_secure_prefs"
        const val KEY_API = "gemini_api_key"
    }
}
