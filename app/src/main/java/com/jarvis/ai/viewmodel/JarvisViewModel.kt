package com.jarvis.ai.viewmodel

import com.jarvis.ai.shizuku.ShizukuBridge

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.ai.core.JarvisCore
import com.jarvis.ai.model.ChatMessage
import com.jarvis.ai.model.JarvisState
import com.jarvis.ai.model.SpeechLanguage
import com.jarvis.ai.screen.ScreenCaptureService
import com.jarvis.ai.settings.JarvisSettings
import com.jarvis.ai.voice.ListeningService
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
    val listening: Boolean = false,
    val statusMessage: String = "",
    val language: SpeechLanguage = SpeechLanguage.ENGLISH_INDIA,
    val speakResponses: Boolean = true,
    val geminiKeySaved: Boolean = false,
    val geminiModel: String = JarvisSettings.DEFAULT_MODEL,
    val settingsMessage: String = "",
    val canWriteSettings: Boolean = false,
    val deviceAdminActive: Boolean = false,
    val accessibilityOn: Boolean = false,
    val shizukuReady: Boolean = false,
    val screenCaptureOn: Boolean = false,
    val automationPaused: Boolean = false
)

/** Thin UI layer. The real work happens in [JarvisCore] and [ListeningService]. */
class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    init {
        JarvisCore.init(application)
    }

    private val app: Application = application
    private val _ui = MutableStateFlow(buildInitialState())
    val ui: StateFlow<JarvisUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            JarvisCore.snapshot.collect { snap ->
                _ui.update { current ->
                    current.copy(
                        state = snap.state,
                        messages = snap.messages,
                        statusMessage = snap.status,
                        partialTranscript = snap.partial,
                        soundLevel = snap.soundLevel,
                        ttsReady = snap.ttsReady,
                        listening = snap.listening,
                        automationPaused = snap.automationPaused
                    )
                }
            }
        }
    }

    private fun buildInitialState(): JarvisUiState {
        val settings = JarvisCore.settings()
        val device = JarvisCore.device()
        val snap = JarvisCore.snapshot.value
        return JarvisUiState(
            state = snap.state,
            messages = snap.messages,
            statusMessage = snap.status,
            listening = snap.listening,
            language = settings.speechLanguage,
            speakResponses = settings.speakResponses,
            geminiKeySaved = JarvisCore.keys().hasKey(),
            geminiModel = settings.geminiModel,
            canWriteSettings = device.canWriteSettings(),
            deviceAdminActive = device.isAdminActive(),
            accessibilityOn = JarvisCore.accessibility().isEnabled(),
            shizukuReady = ShizukuBridge.isReady(),
            screenCaptureOn = ScreenCaptureService.isRunning,
            automationPaused = snap.automationPaused
        )
    }

    init {
        ShizukuBridge.init()
        ShizukuBridge.onChanged = { refreshDevicePermissions() }
    }

    /** Opens Shizuku's permission dialog and shows a short status message. */
    fun requestShizuku() {
        val message = ShizukuBridge.request()
        _ui.update { it.copy(settingsMessage = message) }
        refreshDevicePermissions()
    }

    fun setMicPermission(granted: Boolean) {
        _ui.update { it.copy(micPermissionGranted = granted) }
    }

    /** Toggles background listening. The service keeps running and listens until paused. */
    fun onMicButton() {
        val listening = JarvisCore.snapshot.value.listening
        ListeningService.start(
            app,
            if (listening) ListeningService.ACTION_PAUSE else ListeningService.ACTION_RESUME
        )
    }

    /** Stops speech and the running command. Listening continues. */
    fun emergencyStop() {
        ListeningService.start(app, ListeningService.ACTION_STOP_ALL)
    }

    fun setLanguage(language: SpeechLanguage) {
        JarvisCore.settings().speechLanguage = language
        _ui.update { it.copy(language = language) }
    }

    fun setSpeakResponses(enabled: Boolean) {
        JarvisCore.settings().speakResponses = enabled
        _ui.update { it.copy(speakResponses = enabled) }
    }

    /** Pause automation: Jarvis still understands commands but runs no actions. */
    fun setAutomationPaused(paused: Boolean) {
        JarvisCore.setAutomationPaused(paused)
    }

    // ---- Gemini settings ----

    fun saveApiKey(rawKey: String) {
        val key = rawKey.trim()
        if (key.length < 20) {
            _ui.update { it.copy(settingsMessage = "That key looks too short.") }
            return
        }
        runCatching { JarvisCore.keys().save(key) }
            .onSuccess {
                _ui.update { it.copy(geminiKeySaved = true, settingsMessage = "API key saved securely.") }
            }
            .onFailure { error ->
                _ui.update { it.copy(settingsMessage = "Could not save key: ${error.message}") }
            }
    }

    fun clearApiKey() {
        runCatching { JarvisCore.keys().clear() }
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
        JarvisCore.settings().geminiModel = model
        _ui.update { it.copy(geminiModel = model, settingsMessage = "Model set to $model.") }
    }

    // ---- Device, accessibility, and screen permissions ----

    /** Re-reads permission state. Called when the screen resumes, since the user may change it in Settings. */
    fun refreshDevicePermissions() {
        val device = JarvisCore.device()
        _ui.update {
            it.copy(
                canWriteSettings = device.canWriteSettings(),
                deviceAdminActive = device.isAdminActive(),
                accessibilityOn = JarvisCore.accessibility().isEnabled(),
                shizukuReady = ShizukuBridge.isReady(),
                screenCaptureOn = ScreenCaptureService.isRunning,
                geminiKeySaved = JarvisCore.keys().hasKey()
            )
        }
    }

    fun openWriteSettingsScreen() = JarvisCore.device().openWriteSettingsScreen()

    fun requestDeviceAdmin() = JarvisCore.device().requestDeviceAdmin()

    fun openAccessibilitySettings() = JarvisCore.accessibility().openSettings()

    /** Opens the battery optimization page so background listening is not stopped. */
    fun openBatterySettings() {
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Opens this app's notification settings. */
    fun openNotificationSettings() {
        runCatching {
            app.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
