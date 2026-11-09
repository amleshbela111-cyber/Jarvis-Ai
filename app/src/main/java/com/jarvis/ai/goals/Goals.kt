package com.jarvis.ai.goals

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.ai.ai.GeminiClient
import org.json.JSONArray
import org.json.JSONObject

/** A goal the user set, with the steps Jarvis planned for it. */
data class Goal(val id: Long, val title: String, val steps: List<Step>)
data class Step(val text: String, val done: Boolean)

/**
 * Goals and their steps, kept on the phone. Jarvis plans the steps with Gemini, then works through them
 * one at a time. Light steps run by themselves. Risky steps (sending, buying, deleting, installing) are never
 * run without the user saying so.
 */
object Goals {
    private const val PREFS = "jarvis_goals"
    private const val KEY = "items"
    private var prefs: SharedPreferences? = null

    private val RISKY = Regex(
        "(भेजो|भेज दो|भेजना|send|खरीद|खरीदो|buy|order|पेमेंट|payment|पैसे|pay|मिटाओ|हटाओ|delete|remove|install|इंस्टॉल|uninstall|call|कॉल|post|पोस्ट|पब्लिश|publish|transfer|ट्रांसफ़र|ट्रांसफर)",
        RegexOption.IGNORE_CASE
    )

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** True when a step could cause a real-world effect that must be confirmed first. */
    fun isRisky(step: String): Boolean = RISKY.containsMatchIn(step)

    @Synchronized
    fun all(): List<Goal> {
        val raw = prefs?.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val sa = o.getJSONArray("steps")
                Goal(
                    o.getLong("id"), o.getString("title"),
                    (0 until sa.length()).map { j ->
                        val s = sa.getJSONObject(j)
                        Step(s.getString("text"), s.optBoolean("done", false))
                    }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveAll(list: List<Goal>) {
        val arr = JSONArray()
        list.forEach { g ->
            val sa = JSONArray()
            g.steps.forEach { s -> sa.put(JSONObject().put("text", s.text).put("done", s.done)) }
            arr.put(JSONObject().put("id", g.id).put("title", g.title).put("steps", sa))
        }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
    }

    /** The current goal: the most recent one that still has steps to do. */
    fun current(): Goal? = all().lastOrNull { g -> g.steps.any { !it.done } }

    /** The next step to do in the current goal, or null when there is none. */
    fun nextStep(): Pair<Goal, Int>? {
        val g = current() ?: return null
        val idx = g.steps.indexOfFirst { !it.done }
        return if (idx < 0) null else Pair(g, idx)
    }

    @Synchronized
    fun markStepDone(goalId: Long, index: Int) {
        val list = all().map { g ->
            if (g.id != goalId) g
            else g.copy(steps = g.steps.mapIndexed { i, s -> if (i == index) s.copy(done = true) else s })
        }
        saveAll(list)
    }
}

/** Handles goal, step and decision commands. Returns the reply, or null when the command is not one of them. */
object GoalCommands {
    private val NEW = Regex("^\\s*(?:लक्ष्य|goal)\\s+(?:बनाओ|banao|set|सेट|बनाइए)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val STATUS = Regex("(?:मेरा\\s+)?(?:लक्ष्य|goal)\\s*(?:की\\s+)?(?:प्रगति|progress|स्थिति|status|दिखाओ)", RegexOption.IGNORE_CASE)
    private val NEXT = Regex("(?:अगला|next)\\s+(?:कदम|step)\\s*(?:क्या है|बताओ|बताइए|करो|do|kar do)?", RegexOption.IGNORE_CASE)
    private val DONE = Regex("(?:कदम|step)\\s+(?:पूरा|complete|done)", RegexOption.IGNORE_CASE)
    private val DECIDE = Regex("^\\s*(?:फ़ैसला|फैसला|decide)\\s*(?:लो|करो|do)?\\s*[:,-]?\\s*(.+)$", RegexOption.IGNORE_CASE)

    private const val PLAN_SYSTEM = """You are Jarvis's planner. Break the user's goal into 3 to 6 concrete steps the phone assistant can do one at a time. Keep each step short, in Hinglish. Mark nothing as done. Reply with JSON only: {"title": "short goal title", "steps": ["step 1", "step 2"]}"""

    private const val DECIDE_SYSTEM = """You help the user decide. Compare the options they give, weigh the real pros and cons, and recommend one with a clear reason in two to four Hinglish sentences. Say what you are unsure about. Do not take any action."""

    /**
     * [runStep] runs one light step through Jarvis's normal command path, so device safety rules still apply.
     */
    suspend fun handle(
        command: String,
        apiKey: String?,
        model: String,
        runStep: suspend (String) -> String
    ): String? {
        NEW.find(command)?.let { m ->
            val goalText = m.groupValues[1].trim()
            if (apiKey.isNullOrBlank()) return "Lakshya banane ke liye Gemini key chahiye. Settings mein jaakar key daalein."
            val plan = planGoal(apiKey, model, goalText) ?: return "Lakshya ke kadam nahi ban paaye. Dobara kahiye."
            val id = System.currentTimeMillis()
            Goals.saveAll(Goals.all() + Goal(id, plan.first, plan.second.map { Step(it, false) }))
            val first = plan.second.first()
            // Light first step: Jarvis does it on its own. Risky steps wait for the user.
            if (!Goals.isRisky(first)) {
                val result = runStep(first)
                Goals.markStepDone(id, 0)
                return "Theek hai, lakshya '${plan.first}' ke ${plan.second.size} kadam bana diye. " +
                    "Pehla kadam maine khud kar diya: $first. $result " +
                    if (plan.second.size > 1) "Agla kadam: ${plan.second[1]}. Bolein 'agla kadam karo'." else "Lakshya poora hua."
            }
            return "Theek hai, lakshya '${plan.first}' ke ${plan.second.size} kadam bana diye. " +
                "Pehla kadam '$first' hai, ye kaam aapki haan ke baad hi karunga."
        }
        if (STATUS.containsMatchIn(command)) {
            val g = Goals.current() ?: return "Abhi koi lakshya chal nahi raha."
            val done = g.steps.count { it.done }
            return "Lakshya '${g.title}': ${g.steps.size} mein se $done kadam poore hue."
        }
        if (NEXT.containsMatchIn(command)) {
            val (g, idx) = Goals.nextStep() ?: return "Koi kadam baaki nahi hai."
            val step = g.steps[idx].text
            if (Goals.isRisky(step)) {
                return "Agla kadam '$step' hai. Ye kaam bhejne ya badalne wala hai, isliye pehle aap haan boliye."
            }
            val result = runStep(step)
            Goals.markStepDone(g.id, idx)
            return "Kadam poora kiya: $step. $result"
        }
        if (DONE.containsMatchIn(command)) {
            val (g, idx) = Goals.nextStep() ?: return "Koi kadam baaki nahi hai."
            Goals.markStepDone(g.id, idx)
            return "Theek hai, kadam poora hua: ${g.steps[idx].text}."
        }
        DECIDE.find(command)?.let { m ->
            if (apiKey.isNullOrBlank()) return "Faisla lene ke liye Gemini key chahiye."
            val options = m.groupValues[1].trim()
            return try {
                // The decision also uses what Jarvis remembers about the user and their stated likes.
                val memory = com.jarvis.ai.memory.LongTermMemory.promptContext()
                val userPrompt = (if (memory.isNotEmpty()) memory + "\n\n" else "") + "Options: $options"
                GeminiClient().generate(apiKey, model, DECIDE_SYSTEM, userPrompt, null, false, 4096).trim()
                    .ifBlank { "Faisla nahi ho paaya, dobara kahiye." }
            } catch (_: Exception) {
                "Faisla lene mein dikkat aayi. Dobara kahiye."
            }
        }
        return null
    }

    /** Asks Gemini for the steps. Returns the title and steps, or null when the answer is not usable. */
    private suspend fun planGoal(apiKey: String, model: String, goal: String): Pair<String, List<String>>? {
        val raw = try {
            GeminiClient().generate(apiKey, model, PLAN_SYSTEM, "Goal: $goal", null, true, 4096)
        } catch (_: Exception) {
            return null
        }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(raw.substring(start, end + 1))
            val arr = o.getJSONArray("steps")
            val steps = (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotEmpty() }
            if (steps.isEmpty()) null else Pair(o.optString("title", goal).ifBlank { goal }, steps)
        } catch (_: Exception) {
            null
        }
    }
}
