package com.jarvis.ai.core

import com.jarvis.ai.settings.JarvisSettings

/**
 * Changes Jarvis's own settings from a spoken sentence, such as "awaaz band karo" or "beep band karo".
 * Returns the spoken confirmation, or null when the sentence is not a settings change.
 */
object SettingsCommands {

    private val voiceWord = Regex("awaaz|awaz|voice|bolna|bolne")
    private val offWord = Regex("band|off|mute|chup|mat bolo|mat bolna")
    private val onWord = Regex("chalu|chalu karo|on karo|shuru|start|bajao|bajne do")
    private val modelToken = Regex("model\\s+(?:ko\\s+)?(gemini[a-z0-9._-]*)")

    fun match(raw: String, s: JarvisSettings): String? {
        val t = raw.lowercase().trim()
        if (t.isEmpty() || t.length > 150) return null

        if (voiceWord.containsMatchIn(t)) {
            return when {
                offWord.containsMatchIn(t) -> {
                    s.speakResponses = false
                    "Theek hai, ab main sirf likhkar jawab dunga."
                }
                onWord.containsMatchIn(t) -> {
                    s.speakResponses = true
                    "Haan, ab main bolkar jawab dunga."
                }
                Regex("dheere|dhire|dhima|dheema|slow").containsMatchIn(t) -> {
                    s.voiceSpeed = "slow"
                    "Theek hai, ab main dheere bolunga."
                }
                Regex("tez|fast|jaldi").containsMatchIn(t) -> {
                    s.voiceSpeed = "fast"
                    "Theek hai, ab main thoda tez bolunga."
                }
                Regex("normal|theek").containsMatchIn(t) -> {
                    s.voiceSpeed = "normal"
                    "Theek hai, awaaz ab normal speed par hai."
                }
                else -> null
            }
        }

        if (t.contains("beep")) {
            return when {
                offWord.containsMatchIn(t) -> {
                    s.muteBeep = true
                    "Theek hai, beep band rahega."
                }
                onWord.containsMatchIn(t) -> {
                    s.muteBeep = false
                    "Theek hai, beep chalu kar diya."
                }
                else -> null
            }
        }

        if (t.contains("jawab") || t.contains("answer") || t.contains("reply")) {
            return when {
                Regex("auto|ऑटो|apne aap|apne-aap|khud|seekh").containsMatchIn(t) -> {
                    s.replyStyle = "auto"
                    "Theek hai, ab main aapke feedback se seekh kar jawab ki lambai chununga."
                }
                Regex("chhota|chhote|chota|short|mukhtasar|kam").containsMatchIn(t) -> {
                    s.replyStyle = "short"
                    "Theek hai, ab chhota jawab dunga."
                }
                Regex("lamba|lambe|vistar|detail|poora|pura|bada").containsMatchIn(t) -> {
                    s.replyStyle = "detailed"
                    "Theek hai, ab thoda vistar se jawab dunga."
                }
                else -> null
            }
        }

        if (t.contains("model")) {
            val name = modelToken.find(t)?.groupValues?.get(1) ?: return null
            s.geminiModel = name
            return "Theek hai, model ${s.geminiModel} par set kar diya."
        }
        return null
    }
}
