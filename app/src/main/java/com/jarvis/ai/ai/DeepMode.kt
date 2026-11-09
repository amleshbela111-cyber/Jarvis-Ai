package com.jarvis.ai.ai

/**
 * Decides when a question is complex enough for Gemini's deep mode: a longer answer budget,
 * a detailed reply, and a reminder to check the work before answering.
 */
object DeepMode {
    private val EXPLICIT = Regex("(गहराई से|गहरा सोचो|विस्तार से सोचो|step by step|deep think|think deeply|गहराई में)", RegexOption.IGNORE_CASE)
    private val COMPLEX = Regex(
        "(क्यों|कैसे|तुलना|विश्लेषण|फ़र्क|फर्क|हल करो|गणित|हिसाब|साबित|समझाओ|why|how|analy|compare|difference|prove|solve|calculate|derive|explain)",
        RegexOption.IGNORE_CASE
    )

    fun wants(command: String): Boolean {
        if (EXPLICIT.containsMatchIn(command)) return true
        val words = command.trim().split(Regex("\\s+")).size
        return words >= 18 || COMPLEX.containsMatchIn(command) && words >= 6
    }
}
