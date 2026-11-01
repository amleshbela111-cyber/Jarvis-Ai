package com.jarvis.ai.ai

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.jarvis.ai.accessibility.AccessibilityActions
import com.jarvis.ai.apk.ApkInstaller
import com.jarvis.ai.coding.ProjectWorkspace
import com.jarvis.ai.device.DeviceController
import com.jarvis.ai.device.Outcome
import com.jarvis.ai.screen.ScreenAnalyzer
import java.net.URLEncoder

/**
 * Runs only [ValidatedAction.Ready] actions. Each action type maps to one fixed
 * Android API call or workspace operation. Nothing from Gemini is evaluated as code.
 */
class ActionExecutor(
    private val context: Context,
    private val device: DeviceController,
    private val accessibility: AccessibilityActions,
    private val screenAnalyzer: ScreenAnalyzer,
    private val workspace: ProjectWorkspace,
    private val apk: ApkInstaller
) {

    suspend fun execute(action: ValidatedAction.Ready): Outcome = when (action.type) {
        ActionType.SEARCH_WEB -> {
            val encoded = URLEncoder.encode(action.args.getValue("query"), "UTF-8")
            openIntent(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded")),
                "Search opened in your browser."
            )
        }
        ActionType.OPEN_SETTINGS -> openIntent(
            Intent(settingsActionFor(action.args.getValue("target"))),
            "Settings page opened."
        )
        ActionType.FLASHLIGHT -> device.setFlashlight(action.args.getValue("state") == "on")
        ActionType.ADJUST_VOLUME -> device.setMediaVolume(action.args.getValue("level").toInt())
        ActionType.ADJUST_BRIGHTNESS -> device.setBrightness(action.args.getValue("level").toInt())
        ActionType.LAUNCH_APP -> device.launchApp(action.args["app_name"], action.args["package_name"])
        ActionType.LOCK_SCREEN -> device.lockScreen()
        ActionType.TAP -> accessibility.tap(action.args.getValue("target"))
        ActionType.SCROLL -> accessibility.scroll(action.args.getValue("direction"))
        ActionType.SWIPE -> accessibility.swipe(action.args.getValue("direction"))
        ActionType.INPUT_TEXT -> accessibility.inputText(action.args["target"], action.args.getValue("text"))
        ActionType.READ_SCREEN -> accessibility.readScreen()
        ActionType.NAVIGATE_BACK -> accessibility.back()
        ActionType.GO_HOME -> accessibility.home()
        ActionType.CAPTURE_SCREEN -> screenAnalyzer.answer(
            action.args["question"] ?: ScreenAnalyzer.DEFAULT_QUESTION
        )
        ActionType.CREATE_PROJECT -> workspace.createProject(
            action.args.getValue("name"),
            action.args.getValue("template")
        )
        ActionType.WRITE_FILE -> workspace.writeFile(
            action.args.getValue("project"),
            action.args.getValue("path"),
            action.args.getValue("content")
        )
        ActionType.OPEN_URL -> openIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse(action.args.getValue("url"))),
            "Page browser mein khol di."
        )
        ActionType.DOWNLOAD_FILE -> apk.download(action.args.getValue("url"))
        ActionType.INSTALL_APK -> apk.install(action.args.getValue("file_name"))
        else -> Outcome(false, "${action.type.wire} is not available in this version yet.")
    }

    private fun openIntent(intent: Intent, successMessage: String): Outcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Outcome(true, successMessage)
        } catch (e: ActivityNotFoundException) {
            Outcome(false, "No app on this phone can open that.")
        }
    }

    private fun settingsActionFor(target: String): String = when (target) {
        "wifi" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "display" -> Settings.ACTION_DISPLAY_SETTINGS
        "sound" -> Settings.ACTION_SOUND_SETTINGS
        "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        else -> Settings.ACTION_SETTINGS
    }
}
