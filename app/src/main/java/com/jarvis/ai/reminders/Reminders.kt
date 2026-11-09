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
    const val CHECKIN = "checkin:"
    const val GOALSTEP = "goalstep:"
    private const val CHECKIN_ID = 9001L
    private const val GOALSTEP_ID = 9002L
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

    /** Alarms are lost on reboot or app update, so set them again for every pending reminder and background job. */
    fun rescheduleAll(context: Context) {
        TaskStore.init(context)
        com.jarvis.ai.goals.Goals.init(context)
        TaskStore.all().filter { !it.done && it.dueMs > 0L }.forEach { schedule(context, it.id, it.text, it.dueMs) }
        scheduleCheckin(context)
        if (com.jarvis.ai.goals.Goals.nextStep() != null) scheduleGoalStep(context, 30)
    }

    /** Next morning check-in at 8:00. Alarm ids are fixed so a new one replaces the old. */
    fun scheduleCheckin(context: Context) {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 8)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        schedule(context, CHECKIN_ID, CHECKIN, cal.timeInMillis)
    }

    /** Next background step of the current goal, a few minutes or hours from now. */
    fun scheduleGoalStep(context: Context, minutes: Long) {
        schedule(context, GOALSTEP_ID, GOALSTEP, System.currentTimeMillis() + minutes * 60_000L)
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
        if (text == Reminders.CHECKIN) {
            // Morning check-in: a short summary, then tomorrow's check-in is set.
            Reminders.show(context, id, com.jarvis.ai.goals.Briefing.morningText())
            Reminders.speakAloud(context, com.jarvis.ai.goals.Briefing.morningText())
            Reminders.scheduleCheckin(context)
            return
        }
        if (text == Reminders.GOALSTEP) {
            // Background goal step: only light steps run by themselves. Risky steps wait for the user.
            com.jarvis.ai.goals.Goals.init(context)
            val next = com.jarvis.ai.goals.Goals.nextStep() ?: return
            val (goal, idx) = next
            val step = goal.steps[idx].text
            if (com.jarvis.ai.goals.Goals.isRisky(step)) {
                Reminders.show(context, id, "Lakshya '${goal.title}': agla kadam '$step' aapki haan ke baad hoga.")
                return
            }
            val pending = goAsync()
            com.jarvis.ai.core.JarvisCore.init(context)
            com.jarvis.ai.core.JarvisCore.submit(step) { reply, _ ->
                com.jarvis.ai.goals.Goals.markStepDone(goal.id, idx)
                Reminders.show(context, id, "Lakshya '${goal.title}': kadam poora. $reply")
                if (com.jarvis.ai.goals.Goals.nextStep() != null) Reminders.scheduleGoalStep(context, 120)
                pending.finish()
            }
            return
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
