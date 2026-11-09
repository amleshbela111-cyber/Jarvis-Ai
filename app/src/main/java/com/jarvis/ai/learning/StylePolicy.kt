package com.jarvis.ai.learning

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

/**
 * Reinforcement learning in its simplest form: a bandit with epsilon-greedy choice.
 * Jarvis tries "short" or "detailed" replies. The user's praise (+1) or complaint (-1) about the last
 * reply updates a value for that style (Q = Q + alpha * (reward - Q)). Most of the time it picks the
 * style with the higher value, and sometimes it explores the other one.
 */
object StylePolicy {
    private const val PREFS = "jarvis_policy"
    private const val EPSILON = 0.1
    private const val ALPHA = 0.3
    private val STYLES = listOf("short", "detailed")
    private var prefs: SharedPreferences? = null
    @Volatile private var lastUsed: String? = null

    private val GOOD = Regex("(शाबाश|बहुत अच्छा|बढ़िया जवाब|सही जवाब|good job|well done|great answer|nice answer)", RegexOption.IGNORE_CASE)
    private val BAD = Regex("(गलत|ठीक नहीं|बेकार|wrong|bad answer|not good|that's wrong)", RegexOption.IGNORE_CASE)
    private val ASK = Regex("(मेरी पसंद|मेरी पसंद क्या|my preference|what do i prefer)", RegexOption.IGNORE_CASE)

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Picks the reply style for the next answer. */
    fun choose(): String {
        if (Random.nextDouble() < EPSILON) return STYLES.random()
        return STYLES.maxByOrNull { value(it) } ?: "short"
    }

    fun markUsed(style: String) {
        lastUsed = style
    }

    /** Handles praise, complaints and "meri pasand kya hai". Returns the reply, or null when the command is none of these. */
    @Synchronized
    fun answerIfFeedback(command: String): String? {
        if (ASK.containsMatchIn(command)) {
            val best = STYLES.maxByOrNull { value(it) } ?: "short"
            return "Abhi mujhe aapke liye $best jawab zyada achhe lag rahe hain. Short=${String.format("%.2f", value("short"))}, detailed=${String.format("%.2f", value("detailed"))}."
        }
        val reward = when {
            GOOD.containsMatchIn(command) -> 1.0
            BAD.containsMatchIn(command) -> -1.0
            else -> return null
        }
        val style = lastUsed ?: return "Pehle koi sawaal poochhiye, phir feedback dijiye."
        val updated = value(style) + ALPHA * (reward - value(style))
        prefs?.edit()?.putFloat("q_$style", updated.toFloat())?.apply()
        val best = STYLES.maxByOrNull { value(it) } ?: "short"
        return if (reward > 0) "Shukriya! Main ye style aage bhi rakhunga. Abhi $best pasand aa raha hai."
        else "Samajh gaya, main aage se alag tareeke se jawab dunga. Abhi $best pasand aa raha hai."
    }

    private fun value(style: String): Double = (prefs?.getFloat("q_$style", 0f) ?: 0f).toDouble()
}
