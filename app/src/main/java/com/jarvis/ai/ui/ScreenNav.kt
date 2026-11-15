package com.jarvis.ai.ui

import com.jarvis.ai.study.CodingLanguages
import kotlinx.coroutines.flow.MutableStateFlow

/** A request from voice or touch to open a screen: "settings", "setup", "camera", "coding_study"... Null means no request. */
object ScreenNav {
    val request = MutableStateFlow<String?>(null)

    /** The language named with "coding study", for example "Python". Null when none was named. */
    @Volatile var codingLanguage: String? = null
}

/** Returns the screen to open for a command, or null when the command asks for none. */
fun screenNavFor(command: String): String? {
    val opens = Regex("(khol|kholo|kholiye|open|ओपन|खोल|खोलो|खोलिए|दिखा)", RegexOption.IGNORE_CASE).containsMatchIn(command)
    val cameraWord = Regex("(camera|कैमरा|कैमेरा|कैमरे|कैमेरे)", RegexOption.IGNORE_CASE).containsMatchIn(command)
    // Any spelling speech recognition may give for "live" (लाइव, लाईव, लाइफ, लिव...) means Jarvis's own camera.
    val liveWord = Regex("(\\blive\\b|लाइव|लाईव|लाइफ|लिव|लाइफ़|\\bapna\\b|अपना|jarvis|जार्विस|जार्विश)", RegexOption.IGNORE_CASE).containsMatchIn(command)

    // Coding study: "ओपन कोडिंग स्टडी मोड", "Python sikhao", "मुझे JavaScript सिखाओ".
    val codingWord = Regex("(coding|कोडिंग|कोडींग|कोड|\\bcode\\b)", RegexOption.IGNORE_CASE).containsMatchIn(command)
    val studyWord = Regex("(study|स्टडी|स्टडि|sikh|seekh|learn|सीख|सिखा|लर्न)", RegexOption.IGNORE_CASE).containsMatchIn(command)
    if ((codingWord || CodingLanguages.fromCommand(command) != null) && studyWord) {
        val closes = Regex("(band|बंद|close|बन्द)", RegexOption.IGNORE_CASE).containsMatchIn(command)
        return if (closes) "coding_study_off" else "coding_study"
    }

    if (cameraWord) {
        val closes = Regex("(band|बंद|close|बन्द)", RegexOption.IGNORE_CASE).containsMatchIn(command)
        if (closes) return "camera_off"
        // "Live camera" or "apna camera" opens Jarvis's own camera, even without the word "khol".
        if (liveWord) return "camera"
        // A plain "camera kholo" opens the phone's Camera app.
        if (!opens) return null
        return "system_camera"
    }
    if (!opens) return null
    return when {
        Regex("(setup|सेटअप|सेट अप)", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "setup"
        Regex("(setting|settings|सेटिंग)", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "settings"
        else -> null
    }
}
