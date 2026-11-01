package com.jarvis.ai.ai

/**
 * Every action type the app knows about. The allowlist is closed: anything
 * Gemini returns that is not in this enum is rejected.
 *
 * [executableNow] marks types with a working implementation. Other types are
 * recognized and validated, then reported as "not available yet".
 */
enum class ActionType(val wire: String, val executableNow: Boolean) {
    LAUNCH_APP("launch_app", true),
    OPEN_SETTINGS("open_settings", true),
    ADJUST_BRIGHTNESS("adjust_brightness", true),
    ADJUST_VOLUME("adjust_volume", true),
    FLASHLIGHT("flashlight", true),
    LOCK_SCREEN("lock_screen", true),
    TAP("tap", true),
    SWIPE("swipe", true),
    SCROLL("scroll", true),
    INPUT_TEXT("input_text", true),
    READ_SCREEN("read_screen", true),
    CAPTURE_SCREEN("capture_screen", true),
    NAVIGATE_BACK("navigate_back", true),
    GO_HOME("go_home", true),
    SEARCH_WEB("search_web", true),
    CREATE_PROJECT("create_project", true),
    WRITE_FILE("write_file", true),
    BUILD_APK("build_apk", false),
    DOWNLOAD_FILE("download_file", true),
    OPEN_URL("open_url", true),
    INSTALL_APK("install_apk", true);

    companion object {
        fun fromWire(value: String): ActionType? = values().firstOrNull { it.wire == value }
    }
}

/** An action after validation. Arguments are checked and copied, never passed through raw. */
sealed class ValidatedAction {
    /** Allowed and runnable now. [args] only contains validated values. */
    data class Ready(val type: ActionType, val args: Map<String, String>) : ValidatedAction()

    /** Allowed type, but its implementation is not built yet. */
    data class Pending(val type: ActionType) : ValidatedAction()

    /** Unknown type or invalid arguments. */
    data class Rejected(val rawType: String, val reason: String) : ValidatedAction()
}

data class ValidatedPlan(
    val intent: String,
    val actions: List<ValidatedAction>,
    val requiresConfirmation: Boolean,
    val response: String
)
