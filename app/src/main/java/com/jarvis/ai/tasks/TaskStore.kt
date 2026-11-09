package com.jarvis.ai.tasks

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One task. dueMs is the time of its reminder (0 means no reminder, or the reminder already fired). */
data class Task(val id: Long, val text: String, val dueMs: Long, val done: Boolean)

/** Keeps tasks on the phone only, in SharedPreferences. Nothing is sent anywhere. */
object TaskStore {
    private const val PREFS = "jarvis_tasks"
    private const val KEY = "items"
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun all(): List<Task> {
        val raw = prefs?.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Task(o.getLong("id"), o.getString("text"), o.optLong("due", 0L), o.optBoolean("done", false))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun save(list: List<Task>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("text", t.text).put("due", t.dueMs).put("done", t.done))
        }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
    }

    @Synchronized
    fun add(text: String, dueMs: Long): Task {
        val t = Task(System.currentTimeMillis(), text.trim(), dueMs, false)
        save(all() + t)
        return t
    }

    @Synchronized
    fun replace(task: Task) {
        save(all().map { if (it.id == task.id) task else it })
    }

    @Synchronized
    fun remove(id: Long) {
        save(all().filter { it.id != id })
    }

    /** Finds a pending task whose text contains the spoken words, or the other way round. */
    fun findPending(spoken: String): Task? {
        val needle = spoken.trim().lowercase()
        if (needle.isEmpty()) return null
        return all().filter { !it.done }.firstOrNull {
            val t = it.text.lowercase()
            t.contains(needle) || needle.contains(t)
        }
    }
}
