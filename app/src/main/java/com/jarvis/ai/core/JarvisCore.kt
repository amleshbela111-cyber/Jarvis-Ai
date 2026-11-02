package com.jarvis.ai.core

import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import com.jarvis.ai.accessibility.AccessibilityActions
import com.jarvis.ai.voice.NaturalVoice
import com.jarvis.ai.ai.ActionExecutor
import com.jarvis.ai.ai.GeminiClient
import com.jarvis.ai.ai.JarvisPlanner
import com.jarvis.ai.ai.ValidatedAction
import com.jarvis.ai.ai.ValidatedPlan
import com.jarvis.ai.apk.ApkInstaller
import com.jarvis.ai.coding.ProjectWorkspace
import com.jarvis.ai.device.DeviceController
import com.jarvis.ai.model.ChatMessage
import com.jarvis.ai.model.JarvisState
import com.jarvis.ai.model.Sender
import com.jarvis.ai.safety.ActionGuard
import com.jarvis.ai.screen.ScreenAnalyzer
import com.jarvis.ai.security.ApiKeyStore
import com.jarvis.ai.settings.JarvisSettings
import com.jarvis.ai.storage.ActivityLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The shared brain. The UI and the background listening service both use this object,
 * so a command works the same whether the app is open or another app is in front.
 */
object JarvisCore {

    data class Snapshot(
        val state: JarvisState = JarvisState.IDLE,
        val messages: List<ChatMessage> = emptyList(),
        val status: String = "Jarvis taiyar hai.",
        val partial: String = "",
        val soundLevel: Float = 0f,
        val ttsReady: Boolean = false,
        val listening: Boolean = false,
        val automationPaused: Boolean = false
    )

    class Deps(
        val settings: JarvisSettings,
        val keys: ApiKeyStore,
        val device: DeviceController,
        val accessibility: AccessibilityActions,
        val planner: JarvisPlanner,
        val executor: ActionExecutor
    )

    private data class Pending(val command: String, val plan: ValidatedPlan)

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var pending: Pending? = null
    private var nextId = 1L
    private var silentControl = false
    private var deps: Deps? = null

    private val PLAN_TIMEOUT_MS = 28_000L

    private val SCREEN_CONTROL = setOf(
        com.jarvis.ai.ai.ActionType.TAP,
        com.jarvis.ai.ai.ActionType.SCROLL,
        com.jarvis.ai.ai.ActionType.SWIPE,
        com.jarvis.ai.ai.ActionType.INPUT_TEXT,
        com.jarvis.ai.ai.ActionType.NAVIGATE_BACK,
        com.jarvis.ai.ai.ActionType.GO_HOME
    )

    private val UPDATE_TRIGGER = Regex("(अपडेट|update)\\s+(जार्विस|जारविस|jarvis|jarwis)", RegexOption.IGNORE_CASE)

    private val yes = Regex("\\b(haan|haa|ha|yes|ok|theek hai|confirm|kar do)\\b", RegexOption.IGNORE_CASE)
    private val no = Regex("\\b(nahi|nahin|no|cancel|rehne do|ruko)\\b", RegexOption.IGNORE_CASE)

    /** Call once with any context before using the rest of this object. Safe to call again. */
    fun init(context: Context) {
        if (deps != null) return
        val app = context.applicationContext
        val settings = JarvisSettings(app)
        val keys = ApiKeyStore(app)
        NaturalVoice.apiKeyProvider = { keys.load() }
        val gemini = GeminiClient()
        val device = DeviceController(app)
        val accessibility = AccessibilityActions(app)
        val screen = ScreenAnalyzer(gemini, { keys.load() }, { settings.geminiModel })
        val executor = ActionExecutor(
            app, device, accessibility, screen, ProjectWorkspace(app), ApkInstaller(app)
        )
        deps = Deps(settings, keys, device, accessibility, JarvisPlanner(gemini), executor)
    }

    fun settings(): JarvisSettings = requireDeps().settings
    fun keys(): ApiKeyStore = requireDeps().keys
    fun device(): DeviceController = requireDeps().device
    fun accessibility(): AccessibilityActions = requireDeps().accessibility

    /** Turns any technical error into a short, clear message. Raw error text is never shown. */
    private fun friendlyError(error: Throwable): String {
        val text = (error.message ?: "").lowercase()
        return when {
            error is java.net.SocketTimeoutException || "timeout" in text || "timed out" in text ->
                "Jawab mein thoda time lag gaya. Ek baar phir bolein."
            "404" in text || "no longer available" in text ->
                "Gemini model update hona chahiye. Settings mein model check karein."
            "400" in text || "401" in text || "403" in text || "api key" in text ->
                "API key galat lag rahi hai. Settings mein jaakar check karein."
            else -> "Abhi jawab nahi mil paya. Ek baar phir bolein."
        }
    }

    private fun requireDeps(): Deps = deps ?: throw IllegalStateException("JarvisCore.init was not called")

    /** Runs one command in the background. [onReply] is always called, even with an empty or error reply. */
    fun submit(text: String, onReply: (String, Boolean) -> Unit) {
        job?.cancel()
        job = scope.launch {
            silentControl = false
            val reply = try {
                handle(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                friendlyError(e)
            }
            onReply(reply, !silentControl)
        }
    }

    /** Cancels the running command and any plan waiting for confirmation. */
    fun cancelCurrent() {
        job?.cancel()
        job = null
        pending = null
        _snapshot.update { it.copy(state = JarvisState.IDLE, partial = "", status = "Ruk gaya.") }
    }

    fun setVoiceState(state: JarvisState, status: String) {
        _snapshot.update { it.copy(state = state, status = status) }
    }

    fun setPartial(text: String) {
        _snapshot.update { it.copy(partial = text) }
    }

    fun setSoundLevel(level: Float) {
        _snapshot.update { it.copy(soundLevel = level) }
    }

    fun setTtsReady(ready: Boolean) {
        _snapshot.update { it.copy(ttsReady = ready) }
    }

    fun setListening(on: Boolean) {
        _snapshot.update { it.copy(listening = on) }
    }

    fun setAutomationPaused(paused: Boolean) {
        _snapshot.update { it.copy(automationPaused = paused) }
    }

    private suspend fun handle(text: String): String {
        val command = text.trim()
        if (command.isEmpty()) return ""
        append(Sender.USER, command)
        setVoiceState(JarvisState.THINKING, "Soch raha hoon...")

        val reply = try {
            process(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Kuch galat ho gaya: ${e.message ?: "unknown error"}"
        }
        append(Sender.JARVIS, reply)
        setVoiceState(JarvisState.IDLE, if (_snapshot.value.listening) "Sun raha hoon..." else "Mic band hai.")
        return reply
    }

    private suspend fun process(command: String): String {
        val d = requireDeps()

        if (com.jarvis.ai.update.UpdateFlow.promptMode) {
            com.jarvis.ai.update.UpdateFlow.promptMode = false
            com.jarvis.ai.update.UpdateFlow.draft.value = command
            return "Prompt taiyar hai. Screen par CLAUDE button dabaiye."
        }
        if (UPDATE_TRIGGER.containsMatchIn(command)) {
            com.jarvis.ai.update.UpdateFlow.promptMode = true
            return "Theek hai, ab apna prompt bolein."
        }

        pending?.let { p ->
            pending = null
            if (no.containsMatchIn(command)) {
                ActivityLog.add(p.command, "confirmation", false, "User cancelled.")
                return "Theek hai, action cancel kar diya."
            }
            if (yes.containsMatchIn(command)) return runPlan(d, p.command, p.plan, confirmed = true)
        }

        LocalCommands.match(command)?.let { return runPlan(d, command, it, confirmed = false) }

        val apiKey = d.keys.load() ?: return "Gemini API key set nahi hai. Settings mein jaakar key daalein."
        val history = _snapshot.value.messages
            .dropLast(1)
            .takeLast(2)
            .map { "${if (it.sender == Sender.USER) "User" else "Jarvis"}: ${it.text}" }

        // A hard limit on the whole thinking step, so the user never waits forever.
        val planResult = withTimeoutOrNull(PLAN_TIMEOUT_MS) {
            d.planner.plan(apiKey, d.settings.geminiModel, command, history)
        }
        if (planResult == null) {
            ActivityLog.add(command, "plan", false, "Time limit reached.")
            return "Abhi jawab mein thoda time lag raha hai. Ek baar phir bolein."
        }
        val plan = planResult.getOrElse { error ->
            ActivityLog.add(command, "plan", false, error.message ?: "unknown error")
            return friendlyError(error)
        }
        return runPlan(d, command, plan, confirmed = false)
    }

    private suspend fun runPlan(d: Deps, command: String, plan: ValidatedPlan, confirmed: Boolean): String {
        if (plan.actions.isEmpty()) {
            return plan.response.ifBlank { "Samajh nahi aaya, dobara bolein." }
        }
        if (!confirmed && (plan.requiresConfirmation || ActionGuard.needsConfirmation(plan))) {
            pending = Pending(command, plan)
            return "Ye action chalane se pehle confirm karein: ${ActionGuard.describe(plan)}. " +
                "'haan' bolein to chalaunga, 'nahi' bolein to cancel."
        }
        if (_snapshot.value.automationPaused) {
            ActivityLog.add(command, plan.intent, false, "Automation paused; not run.")
            return "Automation paused hai. Chalane ke liye Settings mein pause band karein."
        }

        // Only confirmed outcomes are reported, so Jarvis never claims an action worked when it did not.
        val results = mutableListOf<String>()
        // Screen controls (tap, swipe, scroll, back, home, typing) run silently. Jarvis only shows the text.
        silentControl = plan.actions.isNotEmpty()
        for (action in plan.actions) {
            when (action) {
                is ValidatedAction.Ready -> {
                    val outcome = d.executor.execute(action)
                    ActivityLog.add(command, action.type.wire, outcome.success, outcome.message)
                    if (!outcome.success || action.type !in SCREEN_CONTROL) silentControl = false
                    results += outcome.message
                }
                is ValidatedAction.Pending -> {
                    ActivityLog.add(command, action.type.wire, false, "Not built yet.")
                    silentControl = false
                    results += "${action.type.wire} abhi supported nahi hai."
                }
                is ValidatedAction.Rejected -> {
                    ActivityLog.add(command, action.rawType, false, action.reason)
                    silentControl = false
                    results += "Action rejected: ${action.reason}."
                }
            }
        }
        return results.joinToString(" ")
    }

    private fun append(sender: Sender, text: String) {
        _snapshot.update { current ->
            current.copy(messages = (current.messages + ChatMessage(nextId++, sender, text)).takeLast(MAX_MESSAGES))
        }
    }

    private const val MAX_MESSAGES = 60
}
