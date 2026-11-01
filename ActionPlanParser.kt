package com.jarvis.ai.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses Gemini's JSON text into a [ValidatedPlan].
 * Malformed JSON, too many actions, unknown types, and bad arguments produce a
 * failure or a Rejected action. Nothing is executed here.
 */
object ActionPlanParser {

    private const val MAX_ACTIONS = 8
    private const val MAX_QUERY_LENGTH = 200
    private const val MAX_RESPONSE_LENGTH = 500
    private const val MAX_LABEL_LENGTH = 100
    private const val MAX_TEXT_LENGTH = 500
    private const val MAX_QUESTION_LENGTH = 300
    private const val MAX_FILE_CONTENT_LENGTH = 50_000
    private const val MAX_PATH_LENGTH = 120

    private val APP_NAME_PATTERN = Regex("[\\p{L}\\p{N} .&'_-]{1,60}")
    private val PACKAGE_PATTERN = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    private val PROJECT_NAME_PATTERN = Regex("[A-Za-z0-9_-]{1,40}")
    private val FILE_PATH_PATTERN = Regex("[A-Za-z0-9_./-]{1,$MAX_PATH_LENGTH}")
    private val HTTPS_URL_PATTERN = Regex("https://[A-Za-z0-9.-]{1,253}(/[A-Za-z0-9._~%/+=-]{0,400})?")
    private val APK_URL_PATTERN = Regex("https://[A-Za-z0-9.-]{1,253}/[A-Za-z0-9._~%/+-]{1,400}\\.apk")
    private val APK_FILE_PATTERN = Regex("[A-Za-z0-9._-]{1,120}\\.apk")

    val SETTINGS_TARGETS: Set<String> = setOf("main", "wifi", "bluetooth", "display", "sound", "location")
    private val SCROLL_DIRECTIONS = setOf("up", "down")
    private val SWIPE_DIRECTIONS = setOf("up", "down", "left", "right")

    fun parse(modelText: String): Result<ValidatedPlan> = runCatching {
        val json = JSONObject(stripCodeFence(modelText))
        val intent = json.optString("intent", "unknown").take(80)
        val response = json.optString("response", "").take(MAX_RESPONSE_LENGTH)
        val requiresConfirmation = json.optBoolean("requires_confirmation", false)

        val rawActions: JSONArray = json.optJSONArray("actions") ?: JSONArray()
        if (rawActions.length() > MAX_ACTIONS) {
            throw IllegalArgumentException("Plan has ${rawActions.length()} actions; the limit is $MAX_ACTIONS")
        }
        val actions = (0 until rawActions.length()).map { index ->
            validateAction(rawActions.optJSONObject(index))
        }
        ValidatedPlan(intent, actions, requiresConfirmation, response)
    }

    private fun validateAction(obj: JSONObject?): ValidatedAction {
        if (obj == null) return ValidatedAction.Rejected("", "Action is not an object")
        val typeName = obj.optString("type")
        val type = ActionType.fromWire(typeName)
            ?: return ValidatedAction.Rejected(typeName, "Unknown action type")

        return when (type) {
            ActionType.SEARCH_WEB -> {
                val query = obj.optString("query").trim()
                if (query.isEmpty() || query.length > MAX_QUERY_LENGTH) {
                    ValidatedAction.Rejected(typeName, "Search query is missing or too long")
                } else {
                    ValidatedAction.Ready(type, mapOf("query" to query))
                }
            }

            ActionType.OPEN_SETTINGS -> {
                val target = obj.optString("target")
                if (target !in SETTINGS_TARGETS) {
                    ValidatedAction.Rejected(typeName, "Settings target not allowed: $target")
                } else {
                    ValidatedAction.Ready(type, mapOf("target" to target))
                }
            }

            ActionType.ADJUST_BRIGHTNESS, ActionType.ADJUST_VOLUME -> {
                val level = readInt(obj, "level")
                if (level == null || level !in 0..100) {
                    ValidatedAction.Rejected(typeName, "Level must be a number from 0 to 100")
                } else {
                    ValidatedAction.Ready(type, mapOf("level" to level.toString()))
                }
            }

            ActionType.FLASHLIGHT -> {
                val state = obj.optString("state").trim().lowercase()
                if (state != "on" && state != "off") {
                    ValidatedAction.Rejected(typeName, "Flashlight state must be 'on' or 'off'")
                } else {
                    ValidatedAction.Ready(type, mapOf("state" to state))
                }
            }

            ActionType.LAUNCH_APP -> {
                val packageName = obj.optString("package_name").trim()
                val appName = obj.optString("app_name").trim()
                when {
                    packageName.isNotEmpty() && PACKAGE_PATTERN.matches(packageName) ->
                        ValidatedAction.Ready(type, mapOf("package_name" to packageName))
                    packageName.isNotEmpty() ->
                        ValidatedAction.Rejected(typeName, "Package name is not valid")
                    appName.isNotEmpty() && APP_NAME_PATTERN.matches(appName) ->
                        ValidatedAction.Ready(type, mapOf("app_name" to appName))
                    else ->
                        ValidatedAction.Rejected(typeName, "App name is missing or has unsupported characters")
                }
            }

            ActionType.TAP -> {
                val target = obj.optString("target").trim()
                if (target.isEmpty() || target.length > MAX_LABEL_LENGTH) {
                    ValidatedAction.Rejected(typeName, "Tap target label is missing or too long")
                } else {
                    ValidatedAction.Ready(type, mapOf("target" to target))
                }
            }

            ActionType.SCROLL -> {
                val direction = obj.optString("direction").trim().lowercase()
                if (direction !in SCROLL_DIRECTIONS) {
                    ValidatedAction.Rejected(typeName, "Scroll direction must be 'up' or 'down'")
                } else {
                    ValidatedAction.Ready(type, mapOf("direction" to direction))
                }
            }

            ActionType.SWIPE -> {
                val direction = obj.optString("direction").trim().lowercase()
                if (direction !in SWIPE_DIRECTIONS) {
                    ValidatedAction.Rejected(typeName, "Swipe direction must be up, down, left, or right")
                } else {
                    ValidatedAction.Ready(type, mapOf("direction" to direction))
                }
            }

            ActionType.INPUT_TEXT -> {
                val text = obj.optString("text")
                val target = obj.optString("target").trim()
                when {
                    text.isEmpty() || text.length > MAX_TEXT_LENGTH ->
                        ValidatedAction.Rejected(typeName, "Text is missing or too long")
                    target.length > MAX_LABEL_LENGTH ->
                        ValidatedAction.Rejected(typeName, "Field name is too long")
                    target.isEmpty() -> ValidatedAction.Ready(type, mapOf("text" to text))
                    else -> ValidatedAction.Ready(type, mapOf("text" to text, "target" to target))
                }
            }

            ActionType.CAPTURE_SCREEN -> {
                val question = obj.optString("question").trim().take(MAX_QUESTION_LENGTH)
                if (question.isEmpty()) {
                    ValidatedAction.Ready(type, emptyMap())
                } else {
                    ValidatedAction.Ready(type, mapOf("question" to question))
                }
            }

            ActionType.CREATE_PROJECT -> {
                val name = obj.optString("name").trim()
                val template = obj.optString("template", "android").trim().lowercase()
                when {
                    !PROJECT_NAME_PATTERN.matches(name) ->
                        ValidatedAction.Rejected(typeName, "Project name may only use letters, digits, '-' and '_'")
                    template != "android" ->
                        ValidatedAction.Rejected(typeName, "Only the 'android' template is available")
                    else -> ValidatedAction.Ready(type, mapOf("name" to name, "template" to template))
                }
            }

            ActionType.WRITE_FILE -> {
                val project = obj.optString("project").trim()
                val path = obj.optString("path").trim()
                val content = obj.optString("content")
                when {
                    !PROJECT_NAME_PATTERN.matches(project) ->
                        ValidatedAction.Rejected(typeName, "Project name is not valid")
                    !FILE_PATH_PATTERN.matches(path) ->
                        ValidatedAction.Rejected(typeName, "File path is not allowed")
                    content.length > MAX_FILE_CONTENT_LENGTH ->
                        ValidatedAction.Rejected(typeName, "File content is too large")
                    else -> ValidatedAction.Ready(
                        type,
                        mapOf("project" to project, "path" to path, "content" to content)
                    )
                }
            }

            ActionType.OPEN_URL -> {
                val url = obj.optString("url").trim()
                if (!HTTPS_URL_PATTERN.matches(url)) {
                    ValidatedAction.Rejected(typeName, "URL must be a plain https:// link")
                } else {
                    ValidatedAction.Ready(type, mapOf("url" to url))
                }
            }

            ActionType.DOWNLOAD_FILE -> {
                val url = obj.optString("url").trim()
                if (!APK_URL_PATTERN.matches(url)) {
                    ValidatedAction.Rejected(typeName, "Download only supports a direct https link ending in .apk")
                } else {
                    ValidatedAction.Ready(type, mapOf("url" to url))
                }
            }

            ActionType.INSTALL_APK -> {
                val fileName = obj.optString("file_name").trim()
                if (!APK_FILE_PATTERN.matches(fileName)) {
                    ValidatedAction.Rejected(typeName, "APK file name is not valid")
                } else {
                    ValidatedAction.Ready(type, mapOf("file_name" to fileName))
                }
            }

            ActionType.LOCK_SCREEN,
            ActionType.READ_SCREEN,
            ActionType.NAVIGATE_BACK,
            ActionType.GO_HOME -> ValidatedAction.Ready(type, emptyMap())

            else -> if (type.executableNow) {
                ValidatedAction.Ready(type, emptyMap())
            } else {
                ValidatedAction.Pending(type)
            }
        }
    }

    private fun readInt(obj: JSONObject, key: String): Int? = when (val raw = obj.opt(key)) {
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull()
        else -> null
    }

    private fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}
