package com.jarvis.ai.study

import com.jarvis.ai.ai.GeminiClient
import org.json.JSONObject

/** One teaching step: the explanation, the code for it, one question to check the learner, and the topic. */
data class TutorStep(
    val reply: String,
    val language: String,
    val code: String,
    val check: String,
    val topic: String = "",
    /** True when the tutor says today's topic is fully taught. The recap starts next. */
    val lessonDone: Boolean = false,
    /** True when the recap questions are answered. The day is then finished. */
    val recapDone: Boolean = false
)

/** The coding teacher. Gemini writes each step; the phone runs the code when it can, and the real output goes back. */
object CodingTutor {

    /** Languages the phone can run right now (the code's language name, lowercase). */
    val RUNNABLE = setOf("javascript", "html", "css", "python")

    suspend fun nextStep(
        client: GeminiClient,
        apiKey: String,
        model: String,
        language: String,
        history: String,
        message: String,
        todayLesson: String? = null,
        recap: Boolean = false
    ): TutorStep {
        val header = "Language being taught: $language\n"
        val today = when {
            todayLesson == null -> ""
            recap -> "RECAP at the end of today's lesson: $todayLesson\n" +
                "Ask the learner exactly 3 short questions about today's topic, one per reply, written in the \"check\" field. " +
                "After each answer, say clearly if it is right, gently correct it if it is not, then ask the next question. " +
                "After the third answer, give a short summary of what the learner learned today and set \"recap_done\" to true.\n\n"
            else -> "TODAY'S DAILY LESSON: $todayLesson\n" +
                "Teach only ONE new topic today, in about 3 to 4 short steps, one step per reply. " +
                "Pick the next topic in the normal learning order of $language for this level, and do not repeat topics already taught. " +
                "Use small examples, and show every idea with code that runs. " +
                "Set \"topic\" to the short title of today's topic. " +
                "When the learner has seen the real output and understood it, set \"lesson_done\" to true.\n\n"
        }
        val lesson = if (history.isBlank()) "" else "Lesson so far:\n$history\n\n"
        val raw = client.generate(
            apiKey = apiKey,
            model = model,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = header + today + lesson + "Learner says: $message",
            expectJson = true,
            maxTokens = 4096
        )
        return parse(raw)
    }

    fun parse(raw: String): TutorStep {
        val json = JSONObject(stripFence(raw))
        return TutorStep(
            reply = json.optString("reply").trim(),
            language = normalize(json.optString("language")),
            code = json.optString("code").trimEnd(),
            check = json.optString("check").trim(),
            topic = json.optString("topic").trim(),
            lessonDone = json.optBoolean("lesson_done", false),
            recapDone = json.optBoolean("recap_done", false)
        )
    }

    fun normalize(language: String): String = when (val name = language.trim().lowercase()) {
        "" -> "none"
        "js", "javascript", "node", "nodejs" -> "javascript"
        "htm", "html" -> "html"
        "py", "python", "python3" -> "python"
        else -> name
    }

    /** Full page for the live preview. CSS gets a small sample page to show on. */
    fun previewFor(language: String, code: String): String =
        if (language == "css") {
            """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>$code</style></head><body><h2>Sample</h2><p>Yeh CSS ka preview hai.</p></body></html>"""
        } else {
            code
        }

    private fun stripFence(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        return t.substringAfter('\n', "").removeSuffix("```").trim()
    }

    private const val SYSTEM_PROMPT = """
You are Jarvis's coding teacher on an Android phone. The learner writes in Hindi, Hinglish or English. Reply in the same mix, mostly Hinglish.

Each message names the language to teach ("Language being taught"). Teach that language, whichever one the learner picks. Start from the very basics (syntax, variables, types, conditions, loops) and go up slowly, one small idea at a time, to advanced and master level (functions, data structures, object-oriented design, error handling, files, standard libraries, concurrency, performance, testing, design patterns and real projects).

Rules:
- Show every idea with code that runs right away on the phone. JavaScript, HTML, CSS and Python run here. Do not only explain; show it running.
- Explain what this step does and why, in 2 to 4 short sentences. Never say in advance what the code will print. The phone runs the code and shows the real output.
- Write the code for this step only. It must be complete and short (under 40 lines). No markdown fences inside the code.
- For JavaScript use console.log. For Python use print(). For HTML write one full page (<!doctype html> ...) with any CSS inside a <style> tag.
- When the message contains "Real output from phone", read that output carefully. If it is correct, move on. If it shows an error, explain the exact cause in simple words and fix it in the code.
- "check" is one short question the learner answers after seeing the real output.
- "topic" is the short title of the lesson topic. Fill it in every reply during a daily lesson.
- For languages that cannot run on this phone (for example Java, Kotlin, C, SQL), still write correct code and explain it line by line, and say that it is not run on the phone.

Return JSON only, with no text outside it, in this exact shape:
{"reply":"explanation","language":"name of the code language in lowercase, or none","code":"code for this step, or empty","check":"one question","topic":"short topic title","lesson_done":false,"recap_done":false}
"""
}
