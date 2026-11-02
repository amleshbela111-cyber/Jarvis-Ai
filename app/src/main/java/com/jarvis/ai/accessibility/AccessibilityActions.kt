package com.jarvis.ai.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.ai.device.Outcome
import com.jarvis.ai.screen.TextFinder
import com.jarvis.ai.shizuku.ShizukuBridge
import com.jarvis.ai.shizuku.ShizukuShell
import kotlinx.coroutines.delay

/**
 * Screen actions for voice commands. When Shizuku is connected, Jarvis uses it (no Accessibility needed).
 * Otherwise it uses the Accessibility service. Each action reports only what was confirmed.
 */
class AccessibilityActions(private val context: Context) {

    /** True when the Accessibility service is running. Shown in Setup. */
    fun isEnabled(): Boolean = JarvisAccessibilityService.instance != null

    private fun shizukuOn(): Boolean = ShizukuBridge.isReady()

    fun openSettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun notEnabled(): Outcome {
        openSettings()
        return Outcome(
            false,
            "Control ke liye Shizuku chalu karein, ya Accessibility on karein. Settings khol di hai."
        )
    }

    /** Retries for a short time, because a newly opened app may still be drawing its screen. */
    suspend fun tap(label: String): Outcome {
        if (shizukuOn()) return shizukuTap(label)
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        repeat(TAP_ATTEMPTS) { attempt ->
            if (service.clickByLabel(label)) return Outcome(true, "Clicked \"$label\".")
            if (attempt < TAP_ATTEMPTS - 1) delay(TAP_RETRY_MS)
        }
        return Outcome(false, "Could not find a clickable \"$label\" on screen. Screen par wo item dikh raha hai ya nahi, check karein.")
    }

    suspend fun scroll(direction: String): Outcome {
        if (shizukuOn()) {
            // Scrolling down means the finger moves up.
            val ok = swipeShizuku(if (direction == "down") "up" else "down")
            return if (ok) Outcome(true, "Scrolled $direction.") else Outcome(false, "Scroll nahi ho paya.")
        }
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        return if (service.scroll(direction)) {
            Outcome(true, "Scrolled $direction.")
        } else {
            Outcome(false, "No scrollable area found on screen.")
        }
    }

    suspend fun swipe(direction: String): Outcome {
        if (shizukuOn()) {
            val ok = swipeShizuku(direction)
            return if (ok) Outcome(true, "Swiped $direction.") else Outcome(false, "Swipe nahi ho paya.")
        }
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        return if (service.swipe(direction)) {
            Outcome(true, "Swiped $direction.")
        } else {
            Outcome(false, "The swipe was cancelled by the system.")
        }
    }

    suspend fun inputText(target: String?, text: String): Outcome {
        // Shizuku's input command types plain ASCII only. Other text goes through Accessibility.
        val ascii = text.all { it.code in 32..126 }
        if (shizukuOn() && ascii) return shizukuType(target, text)
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        val result = service.inputText(target, text)
        return Outcome(result.ok, result.message)
    }

    suspend fun readScreen(): Outcome {
        if (shizukuOn()) {
            val nodes = dumpScreen() ?: return Outcome(false, "Screen padhne mein dikkat hui. Dobara bolein.")
            val text = ScreenTree.readable(nodes, MAX_READ_CHARS)
            return if (text.isBlank()) {
                Outcome(false, "No readable text on screen.")
            } else {
                Outcome(true, "Screen says: $text")
            }
        }
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        val text = service.readVisibleText(MAX_READ_CHARS)
        return if (text.isBlank()) {
            Outcome(false, "No readable text on screen.")
        } else {
            Outcome(true, "Screen says: $text")
        }
    }

    suspend fun back(): Outcome {
        if (shizukuOn()) {
            return if (ShizukuShell.run("input keyevent 4") != null) Outcome(true, "Went back.")
            else Outcome(false, "Back nahi ho paya.")
        }
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        return if (service.performBack()) Outcome(true, "Went back.") else Outcome(false, "Back was not accepted.")
    }

    suspend fun home(): Outcome {
        if (shizukuOn()) {
            return if (ShizukuShell.run("input keyevent 3") != null) Outcome(true, "Went to home screen.")
            else Outcome(false, "Home nahi ho paya.")
        }
        val service = JarvisAccessibilityService.instance ?: return notEnabled()
        return if (service.performHome()) Outcome(true, "Went to home screen.") else Outcome(false, "Home was not accepted.")
    }

    // ---------- Shizuku paths ----------

    private suspend fun shizukuTap(label: String): Outcome {
        repeat(TAP_ATTEMPTS) { attempt ->
            val node = dumpScreen()?.let { ScreenTree.find(it, label) }
            if (node != null) {
                ShizukuShell.run("input tap ${node.cx} ${node.cy}")
                return Outcome(true, "Clicked \"$label\".")
            }
            if (attempt < TAP_ATTEMPTS - 1) delay(TAP_RETRY_MS)
        }
        // Small text that the accessibility tree does not list (games, icons, custom views): look for it on the screenshot.
        val (width, _) = screenSize()
        val point = TextFinder.findCenter(label, width)
        if (point != null && ShizukuShell.run("input tap ${point.first} ${point.second}") != null) {
            return Outcome(true, "Clicked \"$label\".")
        }
        return Outcome(false, "Could not find a clickable \"$label\" on screen. Screen par wo item dikh raha hai ya nahi, check karein.")
    }

    private suspend fun shizukuType(target: String?, text: String): Outcome {
        val wanted = target?.trim().orEmpty()
        if (wanted.isNotEmpty()) {
            val field = dumpScreen()?.let { ScreenTree.findField(it, wanted) }
                ?: return Outcome(false, "\"$wanted\" wali field screen par nahi mili.")
            ShizukuShell.run("input tap ${field.cx} ${field.cy}")
            delay(300)
        }
        // Single quotes are escaped for the shell, and spaces are sent the way `input text` expects.
        val escaped = text.replace("'", "'\\''").replace(" ", "%s")
        return if (ShizukuShell.run("input text '$escaped'") != null) {
            Outcome(true, "Typed the text.")
        } else {
            Outcome(false, "Typing nahi ho paya.")
        }
    }

    private suspend fun swipeShizuku(direction: String): Boolean {
        val (w, h) = screenSize()
        val cx = w / 2
        val cy = h / 2
        val dx = w * 3 / 10
        val dy = h * 3 / 10
        val points = when (direction) {
            "up" -> listOf(cx, cy + dy, cx, cy - dy)
            "down" -> listOf(cx, cy - dy, cx, cy + dy)
            "left" -> listOf(cx + dx, cy, cx - dx, cy)
            else -> listOf(cx - dx, cy, cx + dx, cy)
        }
        return ShizukuShell.run("input swipe ${points[0]} ${points[1]} ${points[2]} ${points[3]} 300") != null
    }

    private suspend fun screenSize(): Pair<Int, Int> {
        val out = ShizukuShell.run("wm size").orEmpty()
        val m = Regex("(\\d+)x(\\d+)").find(out)
        return if (m != null) m.groupValues[1].toInt() to m.groupValues[2].toInt() else 1080 to 2400
    }

    private suspend fun dumpScreen(): List<UiNode>? {
        repeat(2) {
            val xml = ShizukuShell.run(
                "uiautomator dump /data/local/tmp/jarvis_ui.xml >/dev/null 2>&1; cat /data/local/tmp/jarvis_ui.xml 2>/dev/null"
            )
            if (!xml.isNullOrBlank() && xml.contains("<hierarchy")) return ScreenTree.parse(xml)
            delay(400)
        }
        return null
    }

    private companion object {
        const val MAX_READ_CHARS = 600
        const val TAP_ATTEMPTS = 6
        const val TAP_RETRY_MS = 500L
    }
}
