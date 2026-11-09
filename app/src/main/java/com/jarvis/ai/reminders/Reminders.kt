package com.jarvis.ai.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jarvis.ai.tasks.TaskStore

/** Sets and cancels reminder alarms. The alarm fires even when Jarvis is closed. */
object Reminders {
    const val ACTION = "com.jarvis.ai.REMINDER"
    /** Task text that starts with this marks a scheduled routine instead of a plain reminder. */
    const val ROUTINE_PREFIX = "routine:"
    private const val CHANNEL = "jarvis_reminders"
    private var tts: android.speech.tts.TextToSpeech? = null

    /** Speaks a short line with the phone's voice, then frees the speech engine. Works with Jarvis closed. */
    fun speakAloud(context: Context, text: String) {
        try {
            val engine = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    tts?.language = java.util.Locale("en", "IN")
                    tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "reminder")
                }
            }
            tts = engine
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                tts?.shutdown()
                tts = null
            }, 20_000L)
        } catch (_: Exception) {
            // Speech is optional. The notification still shows.
        }
    }

    fun schedule(context: Context, id: Long, text: String, dueMs: Long) {
        if (dueMs <= System.currentTimeMillis()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueMs, pending(context, id, text))
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, id, ""))
    }

    /** Alarms are lost on reboot or app update, so set them again for every pending reminder. */
    fun rescheduleAll(context: Context) {
        TaskStore.init(context)
        TaskStore.all().filter { !it.done && it.dueMs > 0L }.forEach { schedule(context, it.id, it.text, it.dueMs) }
    }

    fun show(context: Context, id: Long, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Jarvis yaad dila raha hai")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id.toInt(), n)
        } catch (_: SecurityException) {
            // Notification permission was not granted. The reminder is still kept in the task list.
        }
    }

    private fun pending(context: Context, id: Long, text: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION)
            .putExtra("id", id)
            .putExtra("text", text)
        return PendingIntent.getBroadcast(
            context,
            (id % Int.MAX_VALUE).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

/** Runs when a reminder alarm fires: shows the notification and clears its reminder time. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Reminders.ACTION) return
        val id = intent.getLongExtra("id", 0L)
        val text = intent.getStringExtra("text") ?: "काम"
        com.jarvis.ai.tasks.TaskStore.init(context)
        com.jarvis.ai.tasks.TaskStore.all().firstOrNull { it.id == id }?.let {
            com.jarvis.ai.tasks.TaskStore.replace(it.copy(dueMs = 0L))
        }
        if (text.startsWith(Reminders.ROUTINE_PREFIX)) {
            // A scheduled routine: run its commands now, and show and speak what Jarvis did.
            val name = text.removePrefix(Reminders.ROUTINE_PREFIX)
            val pending = goAsync()
            com.jarvis.ai.core.JarvisCore.init(context)
            com.jarvis.ai.core.JarvisCore.submit("$name रूटीन चलाओ") { reply, _ ->
                Reminders.show(context, id, reply)
                Reminders.speakAloud(context, reply)
                pending.finish()
            }
            return
        }
        Reminders.show(context, id, text)
        Reminders.speakAloud(context, "Yaad dilaane ka waqt ho gaya: $text")
    }
}
