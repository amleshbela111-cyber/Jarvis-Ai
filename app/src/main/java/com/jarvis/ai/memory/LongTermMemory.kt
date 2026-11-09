package com.jarvis.ai.memory

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Facts the user asks Jarvis to remember, kept on the phone across restarts.
 * They are also given to Gemini with each question, so answers can use them.
 */
object LongTermMemory {
    private const val PREFS = "jarvis_long_memory"
    private const val KEY = "facts"
    private const val MAX = 300
    private var prefs: SharedPreferences? = null

    private val REMEMBER = Regex("^\\s*(?:याद रखो|yaad rakho|remember that|remember)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val FORGET_A = Regex("^\\s*(?:भूल जाओ|bhool jao|forget)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val FORGET_B = Regex("^\\s*(.+?)\\s+(?:भूल जाओ|bhool jao)\\s*$", RegexOption.IGNORE_CASE)
    // Likes and dislikes the user states out loud, for example "मुझे चाय पसंद है" or "मुझे भीड़ पसंद नहीं".
    private val LIKE = Regex("^\\s*(?:मुझे|mujhe)\\s+(.+?)\\s+(?:पसंद है|pasand hai|पसंद)\\s*$", RegexOption.IGNORE_CASE)
    private val DISLIKE = Regex("^\\s*(?:मुझे|mujhe)\\s+(.+?)\\s+(?:पसंद नहीं|pasand nahi|पसंद नहीं है)\\s*$", RegexOption.IGNORE_CASE)
    private val RECALL =Regex("(मेरे बारे में क्या याद|क्या याद है|what do you remember|what do you know about me)", RegexOption.IGNORE_CASE)

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Remember, forget and recall commands. Returns the reply, or null when the command is not about memory. */
    @Synchronized
    fun manage(command: String): String? {
        REMEMBER.find(command)?.let { m ->
            val fact = m.groupValues[1].trim()
            if (fact.isEmpty()) return "Kya yaad rakhun, ye bataiye."
            save((facts().filter { !it.second.equals(fact, ignoreCase = true) } + Pair(System.currentTimeMillis(), fact)).takeLast(MAX))
            return "Theek hai, yaad rakh liya: $fact."
        }
        DISLIKE.find(command)?.let { m ->
            val item = m.groupValues[1].trim()
            save((facts().filter { !it.second.equals("User likes: $item", true) } + Pair(System.currentTimeMillis(), "User dislikes: $item")).takeLast(MAX))
            return "Theek hai, yaad rakh liya ki aapko $item pasand nahi."
        }
        LIKE.find(command)?.let { m ->
            val item = m.groupValues[1].trim()
            if (DISLIKE.containsMatchIn(command)) return null
            save((facts().filter { !it.second.equals("User dislikes: $item", true) } + Pair(System.currentTimeMillis(), "User likes: $item")).takeLast(MAX))
            return "Theek hai, yaad rakh liya ki aapko $item pasand hai."
        }
        (FORGET_A.find(command) ?: FORGET_B.find(command))?.let { m ->
            val needle = m.groupValues[1].trim().lowercase()
            val hit = facts().lastOrNull { it.second.lowercase().contains(needle) || needle.contains(it.second.lowercase()) }
                ?: return "Ye baat meri yaad mein nahi hai."
            save(facts().filter { it.first != hit.first })
            return "Theek hai, bhool gaya: ${hit.second}."
        }
        if (RECALL.containsMatchIn(command)) {
            val list = facts().takeLast(10)
            if (list.isEmpty()) return "Abhi aapne mujhe kuch yaad rakhne ko nahi kaha."
            return "Mujhe ye yaad hai: " + list.joinToString(", ") { it.second } + "."
        }
        return null
    }

    /** The saved facts as a short block for the Gemini prompt. Empty when nothing is saved. */
    @Synchronized
    fun promptContext(): String {
        val list = facts().takeLast(20)
        if (list.isEmpty()) return ""
        return "Things the user asked Jarvis to remember (use them when relevant, do not repeat them unasked):\n" +
            list.joinToString("\n") { "- ${it.second}" }
    }

    private fun facts(): List<Pair<Long, String>> {
        val raw = prefs?.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Pair(o.getLong("t"), o.getString("f"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Pair<Long, String>>) {
        val arr = JSONArray()
        list.forEach { (t, f) -> arr.put(JSONObject().put("t", t).put("f", f)) }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
    }
}
