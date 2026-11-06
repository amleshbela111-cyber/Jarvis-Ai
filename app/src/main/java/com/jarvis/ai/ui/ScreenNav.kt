package com.jarvis.ai.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** A request from voice or touch to open a screen: "settings" or "setup". Null means no request. */
object ScreenNav {
    val request = MutableStateFlow<String?>(null)
}

/** Returns "settings" or "setup" when the command asks to open one of them, otherwise null. */
fun screenNavFor(command: String): String? {
    val opens = Regex("(khol|kholo|kholiye|open|खोल|खोलो|खोलिए|दिखा)", RegexOption.IGNORE_CASE).containsMatchIn(command)
    if (Regex("(camera|कैमरा)", RegexOption.IGNORE_CASE).containsMatchIn(command)) {
        val closes = Regex("(band|बंद|close|बन्द)", RegexOption.IGNORE_CASE).containsMatchIn(command)
        if (closes) return "camera_off"
        if (!opens) return null
        // "Live camera" or "apna camera" opens Jarvis's own camera. A plain "camera kholo" opens the phone's Camera app.
        val live = Regex("(live|लाइव|apna|अपना|jarvis|जार्विस)", RegexOption.IGNORE_CASE).containsMatchIn(command)
        return if (live) "camera" else "system_camera"
    }
    if (!opens) return null
    return when {
        Regex("(setup|सेटअप|सेट अप)", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "setup"
        Regex("(setting|settings|सेटिंग)", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "settings"
        else -> null
    }
}
