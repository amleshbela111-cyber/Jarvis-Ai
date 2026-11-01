package com.jarvis.ai.core

import android.content.Context
import com.jarvis.ai.accessibility.AccessibilityActions
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
    private var deps: Deps? = null

    private val yes = Regex("\\b(haan|haa|ha|yes|ok|theek hai|confirm|kar do)\\b", RegexOption.IGNORE_CASE)
    private val no = Regex("\\b(nahi|nahin|no|cancel|rehne do|ruko)\\b", RegexOption.IGNORE_CASE)

    /** Call once with any context before using the rest of this object. Safe to call again. */
    fun init(context: Context) {
        if (deps != null) return
        val app = context.applicationContext
        val settings = JarvisSettings(app)
        val keys = ApiKeyStore(app)
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

    private fun requireDeps(): Deps = deps ?: throw IllegalStateException("JarvisCore.init was not called")

    /** Runs one command in the background. [onReply] is always called, even with an empty or error reply. */
    fun submit(text: String, onReply: (String) -> Unit) {
        job?.cancel()
        job = scope.launch {
            val reply = handle(text)
            onReply(reply)
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

        val plan = d.planner.plan(apiKey, d.settings.geminiModel, command, history).getOrElse { error ->
            ActivityLog.add(command, "plan", false, error.message ?: "unknown error")
            return "Brain se jawab nahi mila: ${error.message ?: "unknown error"}"
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
        for (action in plan.actions) {
            when (action) {
                is ValidatedAction.Ready -> {
                    val outcome = d.executor.execute(action)
                    ActivityLog.add(command, action.type.wire, outcome.success, outcome.message)
                    results += outcome.message
                }
                is ValidatedAction.Pending -> {
                    ActivityLog.add(command, action.type.wire, false, "Not built yet.")
                    results += "${action.type.wire} abhi supported nahi hai."
                }
                is ValidatedAction.Rejected -> {
                    ActivityLog.add(command, action.rawType, false, action.reason)
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
