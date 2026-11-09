package com.jarvis.ai.tasks

import android.content.Context
import com.jarvis.ai.reminders.Reminders
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Voice commands for tasks and reminders. Everything here runs on the phone, with no Gemini call. */
object TaskCommands {
    private val ADD = Regex("^\\s*(नया |naya )?(काम|task|टास्क)\\s+(जोड़ो|जोड़|add|बनाओ|लिखो|डालो)\\s*[:,-]?\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val SCHEDULE_ROUTINE = Regex("^\\s*(.+?\\s*(?:बजे|baje|am|pm))\\s+(?:पर\\s+)?(.+?)\\s+(?:रूटीन|routine)\\s*(?:चलाओ|run)\\s*$", RegexOption.IGNORE_CASE)
    private val REMIND =Regex("^\\s*(याद दिलाओ|याद दिलाना|remind me( to)?)\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val LIST = Regex("(मेरे\\s+)?(काम|टास्क|task|tasks|रिमाइंडर|reminders?)\\s*(दिखाओ|बताओ|बताइए|सूची|list|बाकी|pending)", RegexOption.IGNORE_CASE)
    private val DONE = Regex("^\\s*(काम|task|टास्क)\\s+(पूरा|complete|done)\\s+(करो\\s+|kar do\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val DELETE = Regex("^\\s*(काम|task|टास्क)\\s+(हटाओ|delete|remove)\\s+(.+)$", RegexOption.IGNORE_CASE)

    private val MINUTES = Regex("(\\d+)\\s*(मिनट|minute|minutes|min)", RegexOption.IGNORE_CASE)
    private val HOURS = Regex("(\\d+)\\s*(घंटे|घंटा|घण्टे|hour|hours|hr)", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("(\\d{1,2})(:(\\d{2}))?\\s*(बजे|baje|am|pm)", RegexOption.IGNORE_CASE)
    private val TOMORROW = Regex("(कल|kal|tomorrow)", RegexOption.IGNORE_CASE)
    private val EVENING = Regex("(शाम|रात|evening|night|pm)", RegexOption.IGNORE_CASE)
    private val STOP = setOf(
        "कल", "सुबह", "शाम", "रात", "में", "बाद", "के", "लिए", "पर", "को", "मुझे",
        "tomorrow", "morning", "evening", "night", "in", "at", "later", "am", "pm", "kal", "baad", "mein", "me", "to"
    )

    /** Returns the spoken reply when the command is about tasks or reminders, otherwise null. */
    fun handle(command: String, context: Context): String? {
        TaskStore.init(context)
        ADD.find(command)?.let { m ->
            val text = m.groupValues[4].trim()
            if (text.isEmpty()) return "Kaunsa kaam jodna hai, ye bataiye."
            TaskStore.add(text, 0L)
            return "Theek hai, kaam jod diya: $text."
        }
        // "रात 9 बजे सुबह रूटीन चलाओ": a routine that runs at the given time.
        SCHEDULE_ROUTINE.find(command)?.let { m ->
            val parsed = parseDue(m.groupValues[1].trim()) ?: return "Kis time par chalaun? Jaise: 9 baje."
            val name = m.groupValues[2].trim()
            if (name.isEmpty()) return "Kaunsa routine chalaun?"
            val due = parsed.second
            val task = TaskStore.add(Reminders.ROUTINE_PREFIX + name, due)
            Reminders.schedule(context, task.id, task.text, due)
            return "Theek hai, ${formatTime(due)} par routine '$name' chalaunga."
        }
        REMIND.find(command)?.let { m ->
            val raw = m.groupValues[3].trim()
            val parsed = parseDue(raw) ?: return "Kis time par yaad dilaun? Jaise: 10 minute mein, ya 8 baje."
            val (text, due) = parsed
            if (text.isEmpty()) return "Kis baat ki yaad dilaun?"
            val task = TaskStore.add(text, due)
            Reminders.schedule(context, task.id, task.text, due)
            return "Theek hai, ${formatTime(due)} par yaad dilaunga: $text."
        }
        DONE.find(command)?.let { m ->
            val task = TaskStore.findPending(m.groupValues[4]) ?: return "Aisa koi kaam nahi mila."
            TaskStore.replace(task.copy(done = true, dueMs = 0L))
            Reminders.cancel(context, task.id)
            return "Ho gaya, ${task.text} pura ho gaya."
        }
        DELETE.find(command)?.let { m ->
            val task = TaskStore.findPending(m.groupValues[3]) ?: return "Aisa koi kaam nahi mila."
            TaskStore.remove(task.id)
            Reminders.cancel(context, task.id)
            return "Theek hai, ${task.text} hata diya."
        }
        if (LIST.containsMatchIn(command)) {
            val pending = TaskStore.all().filter { !it.done }
            if (pending.isEmpty()) return "Aapka koi kaam baaki nahi hai."
            val lines = pending.mapIndexed { i, t ->
                val when_ = if (t.dueMs > 0L) " (${formatTime(t.dueMs)})" else ""
                val label = if (t.text.startsWith(Reminders.ROUTINE_PREFIX)) "routine ${t.text.removePrefix(Reminders.ROUTINE_PREFIX)}" else t.text
                "${i + 1}. $label$when_"
            }
            return "Aapke kaam: " + lines.joinToString(", ") + "."
        }
        return null
    }

    /** Splits "8 baje ... kal" into the text to remember and the time it is due. Null when no time is found. */
    fun parseDue(raw: String, now: Long = System.currentTimeMillis()): Pair<String, Long>? {
        MINUTES.find(raw)?.let { m ->
            return cleanText(raw.removeRange(m.range)) to now + m.groupValues[1].toLong() * 60_000L
        }
        HOURS.find(raw)?.let { m ->
            return cleanText(raw.removeRange(m.range)) to now + m.groupValues[1].toLong() * 3_600_000L
        }
        CLOCK.find(raw)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val minute = if (m.groupValues[3].isEmpty()) 0 else m.groupValues[3].toInt()
            val suffix = m.groupValues[4].lowercase()
            if (hour > 23 || minute > 59) return null
            if (suffix == "pm" || (suffix != "am" && EVENING.containsMatchIn(raw) && hour < 12)) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
            val cal = Calendar.getInstance()
            cal.timeInMillis = now
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (TOMORROW.containsMatchIn(raw)) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
            } else if (cal.timeInMillis <= now) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
            return cleanText(raw.removeRange(m.range)) to cal.timeInMillis
        }
        return null
    }

    private fun cleanText(s: String): String =
        s.split(" ").filter { it.isNotBlank() && it.lowercase() !in STOP }.joinToString(" ").trim()

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("h:mm a, d MMM", Locale("en", "IN")).format(ms)
}
