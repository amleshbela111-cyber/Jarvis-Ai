package com.jarvis.ai.automation

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A routine is a name plus a list of voice commands that run one after another. */
object Routines {
    private const val PREFS = "jarvis_routines"
    private const val KEY = "items"
    private var prefs: SharedPreferences? = null

    private val CREATE = Regex("^\\s*(?:रूटीन|routine)\\s+(?:बनाओ|banao|create|बनाइए)\\s+(.+?)\\s*[:=]\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val RUN_A = Regex("^\\s*(?:रूटीन|routine)\\s+(?:चलाओ|चला दो|चलाइए|run)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val RUN_B = Regex("^\\s*(.+?)\\s+(?:रूटीन|routine)\\s*(?:चलाओ|चला दो|चलाइए|run)\\s*$", RegexOption.IGNORE_CASE)
    private val LIST = Regex("(?:मेरे\\s+)?(?:रूटीन|routines?)\\s*(?:दिखाओ|बताओ|list|सूची)", RegexOption.IGNORE_CASE)
    private val DELETE = Regex("^\\s*(?:रूटीन|routine)\\s+(?:हटाओ|delete|remove)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val COND = Regex("^\\s*(?:अगर|if)\\s+(.+?)\\s*(?:तो|then)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val SPLIT =Regex("\\s*;\\s*|\\s+फिर\\s+|\\s*,\\s*then\\s+|\\s+then\\s+", RegexOption.IGNORE_CASE)

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Create, list, delete: answered here. Returns null when the command is not about routines. */
    fun manage(command: String): String? {
        CREATE.find(command)?.let { m ->
            val name = m.groupValues[1].trim()
            val steps = m.groupValues[2].split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
            if (name.isEmpty() || steps.isEmpty()) return "Routine ka naam aur kam se kam ek command bataiye."
            save(all().filterKeys { !it.equals(name, ignoreCase = true) } + (name to steps))
            return "Routine '$name' ban gaya, ${steps.size} kadam ke saath. Chalane ke liye bolein: $name routine chalao."
        }
        DELETE.find(command)?.let { m ->
            val name = m.groupValues[1].trim()
            val key = find(name) ?: return "Aisa koi routine nahi mila."
            save(all().filterKeys { it != key })
            return "Routine '$key' hata diya."
        }
        if (LIST.containsMatchIn(command)) {
            val items = all()
            if (items.isEmpty()) return "Abhi koi routine nahi bana hai."
            return "Aapke routines: " + items.keys.joinToString(", ") + "."
        }
        return null
    }

    /** The steps of the routine the user asked to run, or null when the command is not a run request. */
    fun stepsToRun(command: String): Pair<String, List<String>>? {
        val name = RUN_A.find(command)?.groupValues?.get(1)
            ?: RUN_B.find(command)?.groupValues?.get(1)
            ?: return null
        val key = find(name.trim()) ?: return Pair(name.trim(), emptyList())
        val steps = (all()[key] ?: emptyList()).mapNotNull { applyCondition(it) }
        return Pair(key, steps)
    }

    /**
     * A step may start with a condition: "अगर रात हो तो ...", "अगर सुबह हो तो ...", "अगर दोपहर हो तो ...",
     * "अगर शाम हो तो ...", "अगर मूड खराब हो तो ...". Returns the command to run, or null to skip the step.
     */
    private fun applyCondition(step: String): String? {
        val m = COND.find(step) ?: return step
        val cond = m.groupValues[1]
        val body = m.groupValues[2].trim()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val holds = when {
            Regex("रात|night", RegexOption.IGNORE_CASE).containsMatchIn(cond) -> hour >= 21 || hour < 5
            Regex("सुबह|morning", RegexOption.IGNORE_CASE).containsMatchIn(cond) -> hour in 5..11
            Regex("दोपहर|afternoon", RegexOption.IGNORE_CASE).containsMatchIn(cond) -> hour in 12..16
            Regex("शाम|evening", RegexOption.IGNORE_CASE).containsMatchIn(cond) -> hour in 17..20
            Regex("मूड|mood", RegexOption.IGNORE_CASE).containsMatchIn(cond) -> com.jarvis.ai.automation.MoodTracker.recentNegative()
            else -> false
        }
        return if (holds) body else null
    }

    private fun find(spoken: String): String? {
        val needle = spoken.trim().lowercase()
        if (needle.isEmpty()) return null
        return all().keys.firstOrNull { val k = it.lowercase(); k == needle || k.contains(needle) || needle.contains(k) }
    }

    @Synchronized
    private fun all(): Map<String, List<String>> {
        val raw = prefs?.getString(KEY, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { k ->
                val arr: JSONArray = obj.getJSONArray(k)
                (0 until arr.length()).map { arr.getString(it) }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @Synchronized
    private fun save(map: Map<String, List<String>>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, JSONArray(v)) }
        prefs?.edit()?.putString(KEY, obj.toString())?.apply()
    }
}
