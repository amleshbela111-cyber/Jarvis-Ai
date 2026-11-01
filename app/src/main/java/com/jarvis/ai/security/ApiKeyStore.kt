package com.jarvis.ai.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ApiKeyStore(context: Context) {

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

    fun save(apiKey: String) {
        prefs.edit().putString(KEY_NAME, apiKey).apply()
    }

    fun load(): String? = runCatching { prefs.getString(KEY_NAME, null) }.getOrNull()

    fun hasKey(): Boolean = runCatching { !prefs.getString(KEY_NAME, null).isNullOrBlank() }.getOrDefault(false)

    fun clear() {
        prefs.edit().remove(KEY_NAME).apply()
    }

    private companion object {
        const val FILE_NAME = "jarvis_secure_prefs"
        const val KEY_NAME = "gemini_api_key"
    }
}
