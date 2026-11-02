package com.jarvis.ai.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Stores the GitHub repo ("owner/repo") and a repo-only token, encrypted on the phone. Reads never throw. */
class GitHubStore(context: Context) {

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

    /** Saves "owner/repo" and the token together. */
    fun save(repo: String, token: String) {
        prefs.edit().putString(REPO_NAME, repo).putString(TOKEN_NAME, token).apply()
    }

    fun repo(): String? = runCatching { prefs.getString(REPO_NAME, null) }.getOrNull()

    fun token(): String? = runCatching { prefs.getString(TOKEN_NAME, null) }.getOrNull()

    fun isConfigured(): Boolean = !repo().isNullOrBlank() && !token().isNullOrBlank()

    fun clear() {
        prefs.edit().remove(REPO_NAME).remove(TOKEN_NAME).apply()
    }

    private companion object {
        const val FILE_NAME = "jarvis_github_prefs"
        const val REPO_NAME = "github_repo"
        const val TOKEN_NAME = "github_token"
    }
}
