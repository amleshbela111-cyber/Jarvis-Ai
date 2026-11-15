package com.jarvis.ai.study

/**
 * The programming languages Jarvis can teach. Each one is recognised from the words a learner might say,
 * in English or Hindi, for example "Python", "पाइथन" or "python sikhao".
 */
object CodingLanguages {

    /** Languages shown as buttons in the popup. Any other language can still be asked for by name. */
    val POPULAR = listOf(
        "JavaScript", "Python", "Java", "Kotlin", "C", "C++", "HTML", "CSS", "SQL", "Go", "Rust", "TypeScript"
    )

    // Longer or more specific names come first, so "C++" is not read as "C" and "JavaScript" not as "Java".
    private val patterns: List<Pair<String, Regex>> = listOf(
        "C++" to Regex("c\\+\\+|c plus plus|सी\\s?प्लस|सी\\s?\\+\\+", RegexOption.IGNORE_CASE),
        "C#" to Regex("c#|c sharp|सी\\s?शार्प", RegexOption.IGNORE_CASE),
        "JavaScript" to Regex("javascript|java script|\\bjs\\b|जावास्क्रिप्ट|जावास्क्रीप्ट", RegexOption.IGNORE_CASE),
        "TypeScript" to Regex("typescript|टाइपस्क्रिप्ट", RegexOption.IGNORE_CASE),
        "Python" to Regex("python|पाइथन|पायथन", RegexOption.IGNORE_CASE),
        "Kotlin" to Regex("kotlin|कोटलिन", RegexOption.IGNORE_CASE),
        "Java" to Regex("\\bjava\\b|जावा", RegexOption.IGNORE_CASE),
        "C" to Regex("\\bc\\b|c language|सी\\s?लैंग्वेज", RegexOption.IGNORE_CASE),
        "HTML" to Regex("\\bhtml\\b|एचटीएमएल", RegexOption.IGNORE_CASE),
        "CSS" to Regex("\\bcss\\b|सीएसएस", RegexOption.IGNORE_CASE),
        "SQL" to Regex("\\bsql\\b|एसक्यूएल", RegexOption.IGNORE_CASE),
        "Go" to Regex("golang|go language", RegexOption.IGNORE_CASE),
        "Rust" to Regex("\\brust\\b|रस्ट", RegexOption.IGNORE_CASE),
        "PHP" to Regex("\\bphp\\b|पीएचपी", RegexOption.IGNORE_CASE),
        "Ruby" to Regex("\\bruby\\b|रूबी", RegexOption.IGNORE_CASE),
        "Swift" to Regex("\\bswift\\b|स्विफ्ट", RegexOption.IGNORE_CASE),
        "Dart" to Regex("\\bdart\\b|डार्ट", RegexOption.IGNORE_CASE),
        "Bash" to Regex("\\bbash\\b|shell script|शेल स्क्रिप्ट", RegexOption.IGNORE_CASE)
    )

    /** The language a sentence asks for, or null when it names none. */
    fun fromCommand(text: String): String? = patterns.firstOrNull { it.second.containsMatchIn(text) }?.first

    /** Turns a saved or spoken name into a known language. Falls back to JavaScript. */
    fun canonical(name: String): String = fromCommand(name) ?: POPULAR.first()

    /** Only these run on the phone right now. Every other language is taught, but its code is not run yet. */
    fun canRunOnPhone(name: String): Boolean = name == "JavaScript" || name == "HTML" || name == "CSS" || name == "Python"
}
