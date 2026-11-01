package com.jarvis.ai.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.ai.device.Outcome

/**
 * Screen actions for voice commands. Each call checks that the accessibility
 * service is running, then reports only what Android confirmed.
 */
class AccessibilityActions(private val context: Context) {

    fun isEnabled(): Boolean = JarvisAccessibilityService.instance != null

    fun openSettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun notEnabled(): Outcome {
        openSettings()
        return Outcome(
            false,
            "Accessibility service band hai. Settings khol di hai. Jarvis AI ko enable karke dobara bolein."
        )
    }

    suspend fun tap(label: String): Outcome {
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        return if (service.clickByLabel(label)) {
            Outcome(true, "Clicked \"$label\".")
        } else {
            Outcome(false, "Could not find a clickable \"$label\" on screen.")
        }
    }

    fun scroll(direction: String): Outcome {
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        return if (service.scroll(direction)) {
            Outcome(true, "Scrolled $direction.")
        } else {
            Outcome(false, "No scrollable area found on screen.")
        }
    }

    suspend fun swipe(direction: String): Outcome {
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        return if (service.swipe(direction)) {
            Outcome(true, "Swiped $direction.")
        } else {
            Outcome(false, "The swipe was cancelled by the system.")
        }
    }

    fun inputText(target: String?, text: String): Outcome {
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        val result = service.inputText(target, text)
        return Outcome(result.ok, result.message)
    }

    fun readScreen(): Outcome {
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        val text = service.readVisibleText(MAX_READ_CHARS)
        return if (text.isBlank()) {
            Outcome(false, "No readable text on screen.")
        } else {
            Outcome(true, "Screen says: $text")
        }
    }

    fun back(): Outcome {
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        return if (service.performBack()) Outcome(true, "Went back.") else Outcome(false, "Back was not accepted.")
    }

    fun home(): Outcome {
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        return if (service.performHome()) Outcome(true, "Went to home screen.") else Outcome(false, "Home was not accepted.")
    }

    private companion object {
        const val MAX_READ_CHARS = 600
    }
}
