package com.jarvis.ai.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** A request from voice or touch to open a screen: "settings" or "setup". Null means no request. */
object ScreenNav {
    val request = MutableStateFlow<String?>(null)
}

/** Returns "settings" or "setup" when the command asks to open one of them, otherwise null. */
fun screenNavFor(command: String): String? {
    val opens = Regex("(khol|kholo|kholiye|open|खोल|खोलो|खोलिए|दिखा)", RegexOption.IGNORE_CASE).containsMatchIn(command)
    val cameraWord = Regex("(camera|कैमरा|कैमेरा|कैमरे|कैमेरे)", RegexOption.IGNORE_CASE).containsMatchIn(command)
    // Any spelling speech recognition may give for "live" (लाइव, लाईव, लाइफ, लिव...) means Jarvis's own camera.
    val liveWord = Regex("(\\blive\\b|लाइव|लाईव|लाइफ|लिव|लाइफ़|\\bapna\\b|अपना|jarvis|जार्विस|जार्विश)", RegexOption.IGNORE_CASE).containsMatchIn(command)
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
