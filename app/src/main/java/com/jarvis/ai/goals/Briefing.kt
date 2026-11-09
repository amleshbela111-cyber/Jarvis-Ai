package com.jarvis.ai.goals

import com.jarvis.ai.tasks.TaskStore
import java.util.Calendar

/** The short morning check-in: open tasks, today's reminders and goal progress. Built only from data on the phone. */
object Briefing {
    fun morningText(): String {
        val pending = TaskStore.all().filter { !it.done && !it.text.startsWith("routine:") }
        val parts = ArrayList<String>()
        parts.add("Suprabhat.")
        if (pending.isEmpty()) {
            parts.add("Aaj koi kaam baaki nahi hai.")
        } else {
            val top = pending.take(3).joinToString(", ") { it.text }
            parts.add("Aapke ${pending.size} kaam baaki hain: $top.")
        }
        val today = Calendar.getInstance()
        val todayReminders = pending.filter { t ->
            t.dueMs > 0L && Calendar.getInstance().apply { timeInMillis = t.dueMs }.let {
                it.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) && it.get(Calendar.YEAR) == today.get(Calendar.YEAR)
            }
        }
        if (todayReminders.isNotEmpty()) {
            parts.add("Aaj ke reminders: " + todayReminders.joinToString(", ") { it.text } + ".")
        }
        Goals.current()?.let { g ->
            val done = g.steps.count { it.done }
            parts.add("Lakshya '${g.title}': ${g.steps.size} mein se $done kadam poore hue.")
        }
        return parts.joinToString(" ")
    }
}
