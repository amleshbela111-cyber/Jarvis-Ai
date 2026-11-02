package com.jarvis.ai.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Helpers for the update flow: a prompt goes to Claude, and the code comes back into Termux.
 * Clipboard is written only from the foreground screen, because Android blocks background apps from it.
 */
object UpdateFlow {

    /** The text in the Jarvis box. Voice commands also fill it. */
    val draft = MutableStateFlow("")

    /** True after "update Jarvis" is said, so the next sentence becomes the prompt. */
    var promptMode = false

    private const val CLAUDE_PACKAGE = "com.anthropic.claude"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val CLAUDE_FALLBACK = "https://claude.ai/new"
    private const val ACTIONS_URL = "https://github.com/amleshbela111-cyber/Jarvis-Ai/actions"

    fun copy(context: Context, text: String) {
        runCatching {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("jarvis", text))
        }
    }

    /** Current clipboard text, or null. Works only while Jarvis is on screen. */
    fun clipboardText(context: Context): String? = runCatching {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(context)?.toString()
    }.getOrNull()

    fun openClaude(context: Context) = openApp(context, CLAUDE_PACKAGE, CLAUDE_FALLBACK)

    fun openTermux(context: Context) = openApp(context, TERMUX_PACKAGE, null)

    fun openGithubActions(context: Context) = openUrl(context, ACTIONS_URL)

    private fun openApp(context: Context, pkg: String, fallbackUrl: String?) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: fallbackUrl?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
            ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
