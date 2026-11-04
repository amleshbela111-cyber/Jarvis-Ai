package com.jarvis.ai.core

import com.jarvis.ai.ai.ActionType
import com.jarvis.ai.ai.ValidatedAction
import com.jarvis.ai.ai.ValidatedPlan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple commands handled on the phone, with no network call, so they answer instantly.
 * Anything not matched here goes to Gemini. Only short commands are matched, so long
 * requests are never misread as a simple command.
 */
object LocalCommands {

    private const val MAX_LOCAL_LENGTH = 60

    private val number = Regex("(\\d{1,3})")
    private val torch = Regex("\\b(torch|flashlight|flash light)\\b")
    private val turnOn = Regex("\\b(on|jalao|chalu|chalao|shuru)\\b")
    private val turnOff = Regex("\\b(off|band|bujha|bujhao)\\b")
    private val time = Regex("\\b(time|samay|baje)\\b")
    private val date = Regex("\\b(date|tarikh|aaj kya hai)\\b")
    private val lock = Regex("\\b(lock|screen lock)\\b")
    private val home = Regex("\\b(home|ghar)\\b")
    private val back = Regex("\\b(back|pichhle|pichle|wapas)\\b")
    private val brightness = Regex("\\b(brightness|roshni)\\b")
    private val volume = Regex("\\b(volume|awaz)\\b")
    private val scrollDown = Regex("\\b(scroll down|neeche)\\b")
    private val scrollUp = Regex("\\b(scroll up|upar)\\b")
    private val tapPrefix = Regex("^(?:tap|click|press|dabao|dabaao|daba do|dabado)\\s+(?:karo\\s+)?(.+)$")
    private val tapSuffix = Regex(
        "^(.+?)\\s+(?:par|pe|ko)?\\s*(?:tap|click|dabao|dabaao|daba do|dabado|dabaiye|press)(?:\\s+karo|\\s+kar do)?$"
    )
    private val openPrefix = Regex("^(?:open|kholo|khol do|launch)\\s+(.+)$")
    private val openSuffix = Regex("^(.+?)\\s+(?:kholo|khol do|open karo|open)$")

    /** Returns a ready plan for a simple command, or null when Gemini should handle it. */
    fun match(raw: String): ValidatedPlan? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_LOCAL_LENGTH) return null
        val lower = text.lowercase(Locale.ROOT)

        if (torch.containsMatchIn(lower)) {
            return when {
                turnOff.containsMatchIn(lower) -> action(ActionType.FLASHLIGHT, mapOf("state" to "off"))
                turnOn.containsMatchIn(lower) -> action(ActionType.FLASHLIGHT, mapOf("state" to "on"))
                else -> null
            }
        }
        if (brightness.containsMatchIn(lower)) {
            val level = number.find(lower)?.value?.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            return action(ActionType.ADJUST_BRIGHTNESS, mapOf("level" to level.toString()))
        }
        if (volume.containsMatchIn(lower)) {
            val level = number.find(lower)?.value?.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            return action(ActionType.ADJUST_VOLUME, mapOf("level" to level.toString()))
        }
        // "Settings tap karo", "Gmail par click karo", "tap Login": tapped directly, with no Gemini call.
        val tapLabel = tapPrefix.find(lower)?.groupValues?.get(1) ?: tapSuffix.find(lower)?.groupValues?.get(1)
        if (tapLabel != null) {
            val clean = tapLabel.trim().trimEnd('.', '!', '?')
            if (clean.isNotEmpty() && clean.split(" ").size <= 4) {
                return ValidatedPlan(
                    ActionType.TAP.wire,
                    listOf(ValidatedAction.Ready(ActionType.TAP, mapOf("target" to clean))),
                    false,
                    ""
                )
            }
        }
        if (scrollDown.containsMatchIn(lower)) return action(ActionType.SCROLL, mapOf("direction" to "down"))
        if (scrollUp.containsMatchIn(lower)) return action(ActionType.SCROLL, mapOf("direction" to "up"))
        if (time.containsMatchIn(lower)) return answer("Abhi ${format("HH:mm")} baje hain.")
        if (date.containsMatchIn(lower)) return answer("Aaj ${format("dd MMMM yyyy")} hai.")
        if (lock.containsMatchIn(lower)) return action(ActionType.LOCK_SCREEN, emptyMap())
        if (home.containsMatchIn(lower) && lower.length < 25) return action(ActionType.GO_HOME, emptyMap())
        if (back.containsMatchIn(lower) && lower.length < 25) return action(ActionType.NAVIGATE_BACK, emptyMap())

        val name = openPrefix.find(lower)?.groupValues?.get(1)
            ?: openSuffix.find(lower)?.groupValues?.get(1)
        if (name != null) {
            val clean = name.trim().trimEnd('.', '!', '?')
            if (clean.isEmpty() || clean.split(" ").size > 3 || clean.contains(" aur ") || clean.contains(" and ")) {
                return null
            }
            if (clean.contains("setting")) return action(ActionType.OPEN_SETTINGS, mapOf("target" to "main"))
            return action(ActionType.LAUNCH_APP, mapOf("app_name" to clean))
        }
        return null
    }

    private fun action(type: ActionType, args: Map<String, String>): ValidatedPlan =
        ValidatedPlan(type.wire, listOf(ValidatedAction.Ready(type, args)), false, "")

    private fun answer(text: String): ValidatedPlan = ValidatedPlan("answer", emptyList(), false, text)

    private fun format(pattern: String): String = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
}
