package com.jarvis.ai.automation

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the mood from the words the user speaks (Hindi and English keywords) and keeps a short
 * history on the phone only. Nothing is sent anywhere.
 */
object MoodTracker {
    private const val PREFS = "jarvis_mood"
    private const val KEY = "entries"
    private const val MAX = 200
    private var prefs: SharedPreferences? = null

    private val NEG = Regex("(उदास|दुखी|परेशान|तनाव|थक|गुस्सा|बोर|डर|बुरा|sad|tired|stressed|angry|upset|bored|worried|anxious)", RegexOption.IGNORE_CASE)
    private val POS = Regex("(खुश|मस्त|बढ़िया|बढ़िया|अच्छा लग|मज़ा|मजा|happy|great|excited|awesome|good mood)", RegexOption.IGNORE_CASE)
    private val ASK = Regex("(मेरा मूड|मेरा मूड कैसा|mood|मूड कैसा)", RegexOption.IGNORE_CASE)

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Looks at one spoken line and records the mood it shows, if any. */
    @Synchronized
    fun record(text: String) {
        val label = when {
            NEG.containsMatchIn(text) -> "neg"
            POS.containsMatchIn(text) -> "pos"
            else -> return
        }
        val list = entries().toMutableList()
        list.add(0, Pair(System.currentTimeMillis(), label))
        save(list.take(MAX))
    }

    /** Reply for "मेरा मूड कैसा है": the mood from the last 24 hours, or null when the question is not about mood. */
    @Synchronized
    fun answerIfAsked(command: String): String? {
        if (!ASK.containsMatchIn(command)) return null
        val since = System.currentTimeMillis() - 24L * 3_600_000L
        val recent = entries().filter { it.first >= since }
        if (recent.isEmpty()) return "Aaj ka mood abhi pata nahi chala. Aap batayein, aap kaisa mehsoos kar rahe hain?"
        val neg = recent.count { it.second == "neg" }
        val pos = recent.count { it.second == "pos" }
        return when {
            neg > pos -> "Pichle 24 ghante mein aap zyada pareshan ya thake hue lage. Agar mann bhari ho to aaram karein, main sath hoon."
            pos > neg -> "Pichle 24 ghante mein aap zyada khush aur active lage. Bahut achha!"
            else -> "Pichle 24 ghante mein mood mixed raha."
        }
    }

    /** True when the user sounded upset or tired within the last [withinMs] milliseconds. */
    @Synchronized
    fun recentNegative(withinMs: Long = 30L * 60_000L): Boolean {
        val since = System.currentTimeMillis() - withinMs
        val latest = entries().firstOrNull { it.first >= since } ?: return false
        return latest.second == "neg"
    }

    /** A short caring line to put in front of the reply, only when the spoken words themselves sound upset. */
    fun empathyLine(command: String): String? =
        if (NEG.containsMatchIn(command)) "Koi baat nahi, main sath hoon. " else null

    private fun entries(): List<Pair<Long, String>> {
        val raw = prefs?.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Pair(o.getLong("t"), o.getString("m"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Pair<Long, String>>) {
        val arr = JSONArray()
        list.forEach { (t, m) -> arr.put(JSONObject().put("t", t).put("m", m)) }
        prefs?.edit()?.putString(KEY, arr.toString())?.apply()
    }
}
