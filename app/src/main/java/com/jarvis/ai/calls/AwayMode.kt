package com.jarvis.ai.calls

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * "Away mode": when the user says they are out, Jarvis reads incoming call notifications and, when the
 * app allows a quick text reply, sends a short text saying that Jarvis is the user's AI assistant,
 * that the user is not available, and that the user will call back.
 *
 * Jarvis never answers a call and never speaks in one. It only uses the reply box in the notification.
 */
object AwayMode {
    private const val TAG = "AwayMode"
    private const val PREFS = "jarvis_away"
    private const val KEY_ON = "on"
    private const val KEY_MISSED = "missed"
    private const val MAX_MISSED = 20
    private const val COOLDOWN_MS = 10L * 60_000L

    /** The reply sent in the notification. It says plainly that the sender is talking to an AI assistant. */
    const val REPLY_TEXT =
        "नमस्ते, मैं Jarvis हूँ, सर का AI असिस्टेंट। सर अभी उपलब्ध नहीं हैं। वे लौटकर आपको कॉल बैक करेंगे।"

    /** Apps whose incoming calls this mode watches. */
    private val CALL_APPS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.facebook.orca",
        "com.google.android.dialer", "com.android.incallui", "com.samsung.android.dialer", "com.truecaller"
    )
    private val CALL_WORDS = Regex("(incoming|calling|is calling|video call|voice call|audio call|incoming call)", RegexOption.IGNORE_CASE)

    private val lastReplyAt = HashMap<String, Long>()

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    /** True when the notification is an incoming call from one of the watched apps. */
    fun looksLikeCall(n: Notification, title: String, text: String, pkg: String): Boolean {
        if (pkg !in CALL_APPS) return false
        return n.category == Notification.CATEGORY_CALL || CALL_WORDS.containsMatchIn("$title $text")
    }

    /** At most one reply per caller in ten minutes, so the same person does not get the text again and again. */
    @Synchronized
    fun cooldownOk(caller: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = lastReplyAt[caller]
        if (last != null && now - last < COOLDOWN_MS) return false
        lastReplyAt[caller] = now
        return true
    }

    /** Records a missed call so Jarvis can tell the user when they come back. */
    @Synchronized
    fun addMissed(context: Context, caller: String, pkg: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = (p.getString(KEY_MISSED, "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()
        list.add("$caller (${pkg.substringAfterLast('.')})")
        p.edit().putString(KEY_MISSED, list.takeLast(MAX_MISSED).joinToString("\n")).apply()
    }

    @Synchronized
    fun missedList(context: Context): List<String> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (p.getString(KEY_MISSED, "") ?: "").split("\n").filter { it.isNotBlank() }
    }

    @Synchronized
    fun clearMissed(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_MISSED).apply()
    }

    /**
     * Sends [text] through the notification's reply box. Returns false when the notification has no reply box.
     * The reply is sent by the notification's own action, so the other app delivers it like any reply.
     */
    fun sendReply(context: Context, action: Notification.Action, text: String): Boolean {
        val inputs = action.remoteInputs ?: return false
        val freeForm = inputs.filter { it.allowFreeFormInput }
        if (freeForm.isEmpty()) return false
        val intent = Intent()
        val bundle = Bundle()
        freeForm.forEach { bundle.putCharSequence(it.resultKey, text) }
        RemoteInput.addResultsToIntent(freeForm.toTypedArray(), intent, bundle)
        return try {
            action.actionIntent.send(context, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Reply could not be sent", e)
            false
        }
    }

    /** Finds the first action in a notification that has a free-text reply box. */
    fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
}
