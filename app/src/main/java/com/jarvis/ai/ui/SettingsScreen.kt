package com.jarvis.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.core.JarvisCore
import com.jarvis.ai.model.SpeechLanguage
import com.jarvis.ai.security.GroqKeyStore
import com.jarvis.ai.voice.PhoneVoices
import androidx.compose.runtime.LaunchedEffect
import com.jarvis.ai.storage.ActivityLog
import com.jarvis.ai.ui.theme.JarvisBackground
import com.jarvis.ai.ui.theme.JarvisCyan
import com.jarvis.ai.ui.theme.JarvisError
import com.jarvis.ai.ui.theme.JarvisMuted
import com.jarvis.ai.ui.theme.JarvisText
import com.jarvis.ai.viewmodel.JarvisUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    ui: JarvisUiState,
    onBack: () -> Unit,
    onLanguageSelected: (SpeechLanguage) -> Unit,
    onSpeakToggle: (Boolean) -> Unit,
    onGeminiVoiceToggle: (Boolean) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onSaveModel: (String) -> Unit,
    onOpenWriteSettings: () -> Unit,
    onEnableDeviceAdmin: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onStopScreenCapture: () -> Unit,
    onAutomationToggle: (Boolean) -> Unit
) {
    var keyInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf(ui.geminiModel) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("BACK", color = JarvisMuted)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "SETTINGS",
                color = JarvisCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }

        SectionTitle("Device permissions")
        PermissionRow(
            label = "Accessibility (tap, scroll, type, read screen)",
            granted = ui.accessibilityOn,
            actionLabel = "Open",
            onAction = onOpenAccessibility
        )
        PermissionRow(
            label = "Screen reading (answers about the screen)",
            granted = ui.screenCaptureOn,
            actionLabel = "Allow",
            onAction = onRequestScreenCapture,
            onRevoke = onStopScreenCapture
        )
        PermissionRow(
            label = "Modify system settings (brightness)",
            granted = ui.canWriteSettings,
            actionLabel = "Open",
            onAction = onOpenWriteSettings
        )
        PermissionRow(
            label = "Device admin (screen lock)",
            granted = ui.deviceAdminActive,
            actionLabel = "Enable",
            onAction = onEnableDeviceAdmin
        )

        SectionTitle("Automation")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Pause automation", color = JarvisText, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Jarvis understands commands but runs no actions while paused. Resets when the app restarts.",
                    color = JarvisMuted,
                    fontSize = 13.sp
                )
            }
            Switch(
                checked = ui.automationPaused,
                onCheckedChange = onAutomationToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = JarvisBackground,
                    checkedTrackColor = JarvisError
                )
            )
        }

        SectionTitle("Gemini brain")
        Text(
            text = if (ui.geminiKeySaved) "API key: saved (encrypted on this phone)" else "API key: not set",
            color = if (ui.geminiKeySaved) JarvisCyan else JarvisMuted,
            fontSize = 13.sp
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = jarvisFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSaveKey(keyInput)
                    keyInput = ""
                },
                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
            ) {
                Text("Save key")
            }
            OutlinedButton(
                onClick = onClearKey,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisError)
            ) {
                Text("Remove key")
            }
        }

        GitHubSection()

        GroqSection()

        VoskSection()

        OutlinedTextField(
            value = modelInput,
            onValueChange = { modelInput = it },
            label = { Text("Gemini model") },
            singleLine = true,
            colors = jarvisFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onSaveModel(modelInput) },
            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
        ) {
            Text("Save model")
        }

        if (ui.settingsMessage.isNotBlank()) {
            Text(text = ui.settingsMessage, color = JarvisText, fontSize = 13.sp)
        }

        SectionTitle("Speech")
        Text(text = "Speech language", color = JarvisText, fontWeight = FontWeight.SemiBold)
        SpeechLanguage.values().forEach { language ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = ui.language == language,
                        onClick = { onLanguageSelected(language) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = ui.language == language,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = JarvisCyan)
                )
                Spacer(Modifier.width(8.dp))
                Text(text = language.label, color = JarvisText)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(text = "Speak responses", color = JarvisText, fontWeight = FontWeight.SemiBold)
                Text(text = "Jarvis reads replies aloud", color = JarvisMuted, fontSize = 13.sp)
            }
            Switch(
                checked = ui.speakResponses,
                onCheckedChange = onSpeakToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = JarvisBackground,
                    checkedTrackColor = JarvisCyan
                )
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Gemini ki awaaz", color = JarvisText, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Off: phone ki built-in awaaz (Hindi ya English, jo upar chuna ho). On: Gemini ki natural awaaz.",
                    color = JarvisMuted,
                    fontSize = 13.sp
                )
            }
            Switch(
                checked = ui.useGeminiVoice,
                onCheckedChange = onGeminiVoiceToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = JarvisBackground,
                    checkedTrackColor = JarvisCyan
                )
            )
        }

        PhoneVoicePicker(ui.language)

        SectionTitle("Background use")
        Text(
            text = "Android stops microphone listening when the app is not on screen. Voice commands work while Jarvis is open. " +
                "To keep Jarvis alive longer, turn off battery optimization for Jarvis AI in Android Settings. Some phone makers still close background apps.",
            color = JarvisMuted,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    onRevoke: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = JarvisText)
            Text(
                text = if (granted) "Granted" else "Not granted",
                color = if (granted) JarvisCyan else JarvisMuted,
                fontSize = 13.sp
            )
        }
        if (!granted) {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
            ) {
                Text(actionLabel)
            }
        } else if (onRevoke != null) {
            OutlinedButton(
                onClick = onRevoke,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisError)
            ) {
                Text("Stop")
            }
        }
    }
}

/** Groq key for Whisper speech-to-text. Stored encrypted; leave it empty to use the phone's recognizer. */
@Composable
private fun GroqSection() {
    val context = LocalContext.current
    val store = remember { GroqKeyStore(context) }
    var saved by remember { mutableStateOf(store.isSet()) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    SectionTitle("Speech (Groq Whisper)")
    Text(
        text = if (saved) "Groq key: saved (encrypted on this phone)" else "Groq key: not set (phone recognizer is used)",
        color = if (saved) JarvisCyan else JarvisMuted,
        fontSize = 13.sp
    )
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text("Groq API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        colors = jarvisFieldColors(),
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                val key = input.trim()
                if (key.length < 20) {
                    message = "Groq key bahut chhoti lag rahi hai."
                } else {
                    runCatching { store.save(key) }
                        .onSuccess { message = "Groq key save ho gayi." }
                        .onFailure { message = "Groq key save nahi ho payi: ${it.message?.take(60)}" }
                    input = ""
                    saved = store.isSet()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
        ) {
            Text("Save key")
        }
        OutlinedButton(
            onClick = {
                runCatching { store.clear() }
                message = "Groq key hata di."
                saved = store.isSet()
            },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisError)
        ) {
            Text("Remove key")
        }
    }
    if (message.isNotBlank()) {
        Text(text = message, color = JarvisText, fontSize = 13.sp)
    }
}

/** Lists the phone's installed voices for the chosen language. Pick the one you like. */
@Composable
private fun PhoneVoicePicker(language: SpeechLanguage) {
    val context = LocalContext.current
    var voices by remember(language) { mutableStateOf<List<String>?>(null) }
    var picked by remember(language) { mutableStateOf(JarvisCore.settings().voiceName(language)) }

    LaunchedEffect(language) {
        PhoneVoices.list(context, language) { voices = it }
    }

    SectionTitle("Awaaz chuno (${language.label})")
    val list = voices
    when {
        list == null -> Text(text = "Awaazein dhoondh raha hoon...", color = JarvisMuted, fontSize = 13.sp)
        list.isEmpty() -> Text(
            text = "Is bhasha ki koi awaaz phone par install nahi hai. Phone ki Text-to-speech settings mein Google ki awaaz download karein.",
            color = JarvisMuted,
            fontSize = 13.sp
        )
        else -> list.forEachIndexed { index, name ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = picked == name,
                        onClick = {
                            picked = name
                            JarvisCore.settings().setVoiceName(language, name)
                        },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = picked == name,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = JarvisCyan)
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "Awaaz ${index + 1}: $name", color = JarvisText, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text.uppercase(), color = JarvisCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

@Composable
private fun jarvisFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = JarvisText,
    unfocusedTextColor = JarvisText,
    focusedBorderColor = JarvisCyan,
    unfocusedBorderColor = JarvisMuted,
    cursorColor = JarvisCyan,
    focusedLabelColor = JarvisCyan,
    unfocusedLabelColor = JarvisMuted
)
