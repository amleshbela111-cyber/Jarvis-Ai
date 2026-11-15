package com.jarvis.ai.study

import android.content.Context
import java.time.LocalDate

/** Which level a day belongs to. Learning goes from the basics up to master, a little at a time. */
object Levels {
    fun forDay(day: Int): String = when {
        day <= 20 -> "Beginner"
        day <= 50 -> "Intermediate"
        day <= 85 -> "Advanced"
        else -> "Master"
    }
}

/**
 * How far the learner is in one language: the next day number, whether today's lesson is done,
 * and the topics already taught, so the tutor does not repeat them. Stored only on the phone.
 */
class StudyProgress(context: Context, private val language: String) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The next day in this language's course, starting at 1. */
    val day: Int
        get() = prefs.getInt(key("day"), 1).coerceAtLeast(1)

    /** True when today's lesson in this language was already finished. */
    fun doneToday(): Boolean = prefs.getString(key("done"), null) == today()

    /** Topics already taught in this language, most recent last. */
    fun covered(): List<String> =
        prefs.getString(key("covered"), "").orEmpty().split("|").filter { it.isNotBlank() }

    /** Called when the tutor says today's lesson is complete. The next day gets the next topic. */
    fun finishToday(topic: String) {
        val list = (covered() + topic.replace("|", "/").trim()).takeLast(60)
        prefs.edit()
            .putInt(key("day"), day + 1)
            .putString(key("done"), today())
            .putString(key("covered"), list.joinToString("|"))
            .apply()
    }

    private fun key(name: String) = "$language.$name"

    private fun today(): String = LocalDate.now().toString()

    companion object {
        private const val FILE = "coding_study"
        private const val LAST_LANGUAGE = "last_language"

        /** The language used last time, so "coding study" without a name opens it again. */
        fun lastLanguage(context: Context): String? =
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LAST_LANGUAGE, null)

        fun rememberLanguage(context: Context, language: String) {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(LAST_LANGUAGE, language).apply()
        }
    }
}
