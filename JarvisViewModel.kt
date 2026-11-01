package com.jarvis.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.ai.accessibility.AccessibilityActions
import com.jarvis.ai.apk.ApkInstaller
import com.jarvis.ai.safety.ActionGuard
import com.jarvis.ai.ai.ActionExecutor
import com.jarvis.ai.ai.GeminiClient
import com.jarvis.ai.ai.JarvisPlanner
import com.jarvis.ai.ai.ValidatedAction
import com.jarvis.ai.ai.ValidatedPlan
import com.jarvis.ai.coding.ProjectWorkspace
import com.jarvis.ai.device.DeviceController
import com.jarvis.ai.model.ChatMessage
import com.jarvis.ai.model.JarvisState
import com.jarvis.ai.model.Sender
import com.jarvis.ai.model.SpeechLanguage
import com.jarvis.ai.screen.ScreenAnalyzer
import com.jarvis.ai.screen.ScreenCaptureService
import com.jarvis.ai.security.ApiKeyStore
import com.jarvis.ai.settings.JarvisSettings
import com.jarvis.ai.storage.ActivityLog
import com.jarvis.ai.voice.VoiceController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JarvisUiState(
    val state: JarvisState = JarvisState.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    val partialTranscript: String = "",
    val soundLevel: Float = 0f,
    val micPermissionGranted: Boolean = false,
    val ttsReady: Boolean = false,
    val statusMessage: String = "Tap the mic and speak.",
    val language: SpeechLanguage = SpeechLanguage.ENGLISH_INDIA,
    val speakResponses: Boolean = true,
    val geminiKeySaved: Boolean = false,
    val geminiModel: String = JarvisSettings.DEFAULT_MODEL,
    val settingsMessage: String = "",
    val canWriteSettings: Boolean = false,
    val deviceAdminActive: Boolean = false,
    val accessibilityOn: Boolean = false,
    val screenCaptureOn: Boolean = false,
    val automationPaused: Boolean = false
)

class JarvisViewModel(application: Application) :
    AndroidViewModel(application),
    VoiceController.Listener {

    private val settings = JarvisSettings(application)
    private val apiKeys = ApiKeyStore(application)
    private val gemini = GeminiClient()
    private val planner = JarvisPlanner(gemini)
    private val device = DeviceController(application)
    private val accessibility = AccessibilityActions(application)
    private val screenAnalyzer = ScreenAnalyzer(
        gemini = gemini,
        apiKeyProvider = { apiKeys.load() },
        modelProvider = { settings.geminiModel }
    )
    private val workspace = ProjectWorkspace(application)
    private val apk = ApkInstaller(application)
    private val executor = ActionExecutor(application, device, accessibility, screenAnalyzer, workspace, apk)
    private val voice = VoiceController(application, this)

    private val _ui = MutableStateFlow(
        JarvisUiState(
            language = settings.speechLanguage,
            speakResponses = settings.speakResponses,
            geminiKeySaved = apiKeys.hasKey(),
            geminiModel = settings.geminiModel,
            canWriteSettings = device.canWriteSettings(),
            deviceAdminActive = device.isAdminActive(),
            accessibilityOn = accessibility.isEnabled(),
            screenCaptureOn = ScreenCaptureService.isRunning
        )
    )
    val ui: StateFlow<JarvisUiState> = _ui.asStateFlow()

    private var nextMessageId = 1L
    private var planJob: Job? = null

    /** A plan waiting for the user's "haan" or "nahi". */
    private data class PendingPlan(val command: String, val plan: ValidatedPlan)
    private var pendingPlan: PendingPlan? = null

    private companion object {
        val YES_PATTERN = Regex("\\b(haan|haa|ha|yes|ok|theek hai|confirm|kar do)\\b", RegexOption.IGNORE_CASE)
        val NO_PATTERN = Regex("\\b(nahi|nahin|no|cancel|rehne do|ruko)\\b", RegexOption.IGNORE_CASE)
    }

    init {
        voice.setLanguage(settings.speechLanguage)
    }

    fun setMicPermission(granted: Boolean) {
        _ui.update {
            it.copy(
                micPermissionGranted = granted,
                statusMessage = if (granted) "Tap the mic and speak." else "Microphone permission is needed."
            )
        }
    }

    /** Called when the mic button is tapped while permission is already granted. */
    fun onMicButton() {
        if (_ui.value.state == JarvisState.LISTENING) {
            voice.stopListening()
            return
        }
        voice.stopSpeaking()
        _ui.update {
            it.copy(state = JarvisState.LISTENING, partialTranscript = "", statusMessage = "Listening...")
        }
        voice.startListening()
    }

    /** Stops listening, speech, and any pending Gemini or device action, then returns to idle. */
    fun emergencyStop() {
        planJob?.cancel()
        planJob = null
        voice.stopListening()
        voice.stopSpeaking()
        _ui.update {
            it.copy(
                state = JarvisState.IDLE,
                partialTranscript = "",
                soundLevel = 0f,
                statusMessage = "Stopped."
            )
        }
    }

    fun setLanguage(language: SpeechLanguage) {
        settings.speechLanguage = language
        voice.setLanguage(language)
        _ui.update { it.copy(language = language) }
    }

    fun setSpeakResponses(enabled: Boolean) {
        settings.speakResponses = enabled
        if (!enabled) voice.stopSpeaking()
        _ui.update { it.copy(speakResponses = enabled) }
    }

    /** Pause: Jarvis still understands commands but does not run any actions until resumed. */
    fun setAutomationPaused(paused: Boolean) {
        _ui.update { it.copy(automationPaused = paused) }
    }

    // ---- Gemini settings ----

    fun saveApiKey(rawKey: String) {
        val key = rawKey.trim()
        if (key.length < 20) {
            _ui.update { it.copy(settingsMessage = "That key looks too short.") }
            return
        }
        runCatching { apiKeys.save(key) }
            .onSuccess {
                _ui.update { it.copy(geminiKeySaved = true, settingsMessage = "API key saved securely.") }
            }
            .onFailure { error ->
                _ui.update { it.copy(settingsMessage = "Could not save key: ${error.message}") }
            }
    }

    fun clearApiKey() {
        runCatching { apiKeys.clear() }
        _ui.update { it.copy(geminiKeySaved = false, settingsMessage = "API key removed.") }
    }

    fun saveGeminiModel(rawModel: String) {
        val model = rawModel.trim()
        if (!Regex("[A-Za-z0-9._-]{1,64}").matches(model)) {
            _ui.update {
                it.copy(settingsMessage = "Model name may only contain letters, digits, '.', '_' and '-'.")
            }
            return
        }
        settings.geminiModel = model
        _ui.update { it.copy(geminiModel = model, settingsMessage = "Model set to $model.") }
    }

    // ---- Device, accessibility, and screen permissions ----

    /** Re-reads permission state. Called each time the screen resumes, since the user may change it in Settings. */
    fun refreshDevicePermissions() {
        _ui.update {
            it.copy(
                canWriteSettings = device.canWriteSettings(),
                deviceAdminActive = device.isAdminActive(),
                accessibilityOn = accessibility.isEnabled(),
                screenCaptureOn = ScreenCaptureService.isRunning
            )
        }
    }

    fun openWriteSettingsScreen() = device.openWriteSettingsScreen()

    fun requestDeviceAdmin() = device.requestDeviceAdmin()

    fun openAccessibilitySettings() = accessibility.openSettings()

    // ---- VoiceController.Listener ----

    override fun onListeningStarted() {
        _ui.update { it.copy(state = JarvisState.LISTENING, statusMessage = "Listening...") }
    }

    override fun onSoundLevel(level: Float) {
        _ui.update { it.copy(soundLevel = level) }
    }

    override fun onPartialResult(text: String) {
        _ui.update { it.copy(partialTranscript = text) }
    }

    override fun onFinalResult(text: String) {
        handleUserCommand(text.trim())
    }

    override fun onListeningStopped() {
        _ui.update {
            if (it.state == JarvisState.LISTENING) {
                it.copy(state = JarvisState.IDLE, soundLevel = 0f)
            } else {
                it.copy(soundLevel = 0f)
            }
        }
    }

    override fun onVoiceError(message: String) {
        _ui.update { it.copy(state = JarvisState.ERROR, statusMessage = message, partialTranscript = "") }
    }

    override fun onSpeechStarted() {
        _ui.update { it.copy(state = JarvisState.SPEAKING, statusMessage = "Speaking...") }
    }

    override fun onSpeechFinished() {
        _ui.update {
            if (it.state == JarvisState.SPEAKING) {
                it.copy(state = JarvisState.IDLE, statusMessage = "Tap the mic and speak.")
            } else {
                it
            }
        }
    }

    override fun onTtsReadyChanged(ready: Boolean) {
        _ui.update { it.copy(ttsReady = ready) }
    }

    // ---- Command handling ----

    private fun handleUserCommand(text: String) {
        if (text.isBlank()) {
            _ui.update { it.copy(state = JarvisState.IDLE, partialTranscript = "") }
            return
        }
        addMessage(Sender.USER, text)

        // A plan waiting for confirmation takes priority over a new command.
        pendingPlan?.let { pending ->
            pendingPlan = null
            when {
                NO_PATTERN.containsMatchIn(text) -> {
                    ActivityLog.add(pending.command, "confirmation", false, "User cancelled.")
                    reply("Theek hai, action cancel kar diya.")
                    return
                }
                YES_PATTERN.containsMatchIn(text) -> {
                    _ui.update { it.copy(state = JarvisState.EXECUTING, partialTranscript = "", statusMessage = "Running...") }
                    planJob = viewModelScope.launch {
                        reply(composeReply(pending.command, pending.plan, confirmed = true))
                        refreshDevicePermissions()
                    }
                    return
                }
            }
        }

        val apiKey = runCatching { apiKeys.load() }.getOrNull()
        if (apiKey == null) {
            reply("Gemini API key set nahi hai. Settings mein jaakar key daalein.")
            return
        }

        _ui.update { it.copy(state = JarvisState.THINKING, partialTranscript = "", statusMessage = "Thinking...") }

        val state = _ui.value
        val history = state.messages
            .dropLast(1) // the current command is sent separately
            .takeLast(6)
            .map { message ->
                val speaker = if (message.sender == Sender.USER) "User" else "Jarvis"
                "$speaker: ${message.text}"
            }

        planJob = viewModelScope.launch {
            planner.plan(apiKey, state.geminiModel, text, history)
                .onSuccess { plan -> reply(composeReply(text, plan)) }
                .onFailure { error ->
                    ActivityLog.add(text, "plan", false, error.message ?: "unknown error")
                    reply("Brain se jawab nahi mila: ${error.message ?: "unknown error"}")
                }
            refreshDevicePermissions()
        }
    }

    /** Runs the allowed actions, logs each result, and builds the spoken and visible reply. */
    private suspend fun composeReply(command: String, plan: ValidatedPlan, confirmed: Boolean = false): String {
        val parts = mutableListOf<String>()
        parts += plan.response.ifBlank { "Theek hai." }

        if (plan.actions.isEmpty()) return parts.joinToString(" ")

        if (!confirmed && (plan.requiresConfirmation || ActionGuard.needsConfirmation(plan))) {
            pendingPlan = PendingPlan(command, plan)
            parts += "Ye action chalane se pehle confirm karein: ${ActionGuard.describe(plan)}. 'haan' bolein to chalaunga, 'nahi' bolein to cancel."
            ActivityLog.add(command, plan.intent, false, "Waiting for confirmation.")
            return parts.joinToString(" ")
        }

        if (_ui.value.automationPaused) {
            parts += "Automation paused hai. Chalane ke liye pause band karein."
            ActivityLog.add(command, plan.intent, false, "Automation paused; not run.")
            return parts.joinToString(" ")
        }

        plan.actions.forEach { action ->
            when (action) {
                is ValidatedAction.Ready -> {
                    val outcome = executor.execute(action)
                    ActivityLog.add(command, action.type.wire, outcome.success, outcome.message)
                    parts += outcome.message
                }
                is ValidatedAction.Pending -> {
                    ActivityLog.add(command, action.type.wire, false, "Not built yet.")
                    parts += "${action.type.wire} abhi supported nahi hai."
                }
                is ValidatedAction.Rejected -> {
                    ActivityLog.add(command, action.rawType, false, action.reason)
                    parts += "Action rejected: ${action.reason}."
                }
            }
        }
        return parts.joinToString(" ")
    }

    private fun reply(text: String) {
        addMessage(Sender.JARVIS, text)
        val state = _ui.value
        if (state.speakResponses && state.ttsReady && voice.speak(text)) {
            // onSpeechStarted and onSpeechFinished drive the state from here.
            return
        }
        _ui.update { it.copy(state = JarvisState.IDLE, statusMessage = "Tap the mic and speak.") }
    }

    private fun addMessage(sender: Sender, text: String) {
        _ui.update {
            it.copy(messages = it.messages + ChatMessage(nextMessageId++, sender, text))
        }
    }

    override fun onCleared() {
        planJob?.cancel()
        voice.release()
        super.onCleared()
    }
}
