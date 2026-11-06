package com.jarvis.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.media.projection.MediaProjectionManager
import com.jarvis.ai.screen.ScreenCaptureService
import com.jarvis.ai.model.ChatMessage
import com.jarvis.ai.model.JarvisState
import com.jarvis.ai.model.Sender
import com.jarvis.ai.ui.theme.JarvisBackground
import com.jarvis.ai.ui.theme.JarvisCyan
import com.jarvis.ai.ui.theme.JarvisError
import com.jarvis.ai.ui.theme.JarvisMuted
import com.jarvis.ai.ui.theme.JarvisSurface
import com.jarvis.ai.ui.theme.JarvisText
import com.jarvis.ai.viewmodel.JarvisUiState
import com.jarvis.ai.viewmodel.JarvisViewModel
import com.jarvis.ai.voice.ListeningService
import com.jarvis.ai.update.UpdateFlow
import com.jarvis.ai.core.NeuralView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn

/** Root composable: decides between the home screen and settings. */
@Composable
fun JarvisApp(viewModel: JarvisViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSetup by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setMicPermission(granted)
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(context, result.resultCode, data)
        }
        viewModel.refreshDevicePermissions()
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setMicPermission(granted)
    }

    // Listening no longer starts when the app opens, so the battery is not used until Jarvis is turned on
    // (with the mic button, the orb, or the Quick Settings tile).

    LifecycleResumeEffect(Unit) {
        viewModel.refreshDevicePermissions()
        onPauseOrDispose {}
    }

    Box(Modifier.fillMaxSize().background(JarvisBackground)) {
        if (showSetup) {
            SetupScreen(
                ui = ui,
                onBack = { showSetup = false },
                onOpenAccessibility = viewModel::openAccessibilitySettings,
                onOpenWriteSettings = viewModel::openWriteSettingsScreen,
                onEnableDeviceAdmin = viewModel::requestDeviceAdmin,
                onRequestScreenCapture = {
                    val mpm = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                },
                onOpenBattery = viewModel::openBatterySettings,
                onOpenNotifications = viewModel::openNotificationSettings,
                onOpenMicPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onRequestShizuku = viewModel::requestShizuku
            )
        } else if (showSettings) {
            SettingsScreen(
                ui = ui,
                onBack = { showSettings = false },
                onLanguageSelected = viewModel::setLanguage,
                onSpeakToggle = viewModel::setSpeakResponses,
                onGeminiVoiceToggle = viewModel::setUseGeminiVoice,
                onSaveKey = viewModel::saveApiKey,
                onClearKey = viewModel::clearApiKey,
                onSaveModel = viewModel::saveGeminiModel,
                onOpenWriteSettings = viewModel::openWriteSettingsScreen,
                onEnableDeviceAdmin = viewModel::requestDeviceAdmin,
                onOpenAccessibility = viewModel::openAccessibilitySettings,
                onRequestScreenCapture = {
                    val mpm = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                },
                onStopScreenCapture = { ScreenCaptureService.stop(context) },
                onAutomationToggle = viewModel::setAutomationPaused
            )
        } else {
            JarvisHome(
                ui = ui,
                onOpenSettings = { showSettings = true },
                onOpenSetup = { showSetup = true },
                onMicClick = {
                    if (ui.micPermissionGranted) {
                        viewModel.onMicButton()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStop = viewModel::emergencyStop
            )
        }
    }
}

@Composable
private fun JarvisHome(
    ui: JarvisUiState,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
    onMicClick: () -> Unit,
    onStop: () -> Unit
) {
    val listState = rememberLazyListState()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val showUpdate by UpdateFlow.panelVisible.collectAsState()
    val neuralAnswer by NeuralView.answer.collectAsState()
    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "JARVIS",
                color = JarvisCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            // Three lines at the top right. Tapping them opens the menu with Settings and Setup.
            Box {
                Column(
                    modifier = Modifier
                        .clickable { menuOpen = true }
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .height(2.dp)
                                .background(JarvisCyan)
                        )
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = {
                            menuOpen = false
                            onOpenSettings()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Setup") },
                        onClick = {
                            menuOpen = false
                            onOpenSetup()
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        StatusRow(ui)
        Spacer(Modifier.height(12.dp))

        // The orb and its status sit in the middle of the free space.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
        if (neuralAnswer != null) {
            NeuralGraph(state = ui.state, soundLevel = ui.soundLevel)
            Text(
                text = neuralAnswer.orEmpty(),
                color = JarvisText,
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp)
            )
            TextButton(onClick = { NeuralView.answer.value = null }) {
                Text("ORB PAR LAUT CHALO", color = JarvisMuted)
            }
        } else {
            JarvisOrbGl(
                state = ui.state,
                soundLevel = ui.soundLevel,
                onTap = onMicClick
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = ui.statusMessage,
            color = JarvisText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (ui.partialTranscript.isNotBlank()) {
            Text(
                text = ui.partialTranscript,
                color = JarvisMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = 220.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ui.messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }

        if (showUpdate) UpdatePanel()

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatusRow(ui: JarvisUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(label = "MIC", value = if (ui.micPermissionGranted) "OK" else "NEEDED", ok = ui.micPermissionGranted)
        StatusChip(label = "VOICE", value = if (ui.ttsReady) "READY" else "LOADING", ok = ui.ttsReady)
        StatusChip(label = "GEMINI", value = if (ui.geminiKeySaved) "KEY SAVED" else "NO KEY", ok = ui.geminiKeySaved)
    }
}

@Composable
private fun StatusChip(label: String, value: String, ok: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = JarvisSurface,
        border = BorderStroke(1.dp, if (ok) JarvisCyan else JarvisMuted)
    ) {
        Text(
            text = "$label: $value",
            color = if (ok) JarvisCyan else JarvisMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.sender == Sender.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isUser) JarvisSurface else JarvisSurface.copy(alpha = 0.6f),
            border = BorderStroke(
                1.dp,
                if (isUser) JarvisCyan.copy(alpha = 0.6f) else JarvisCyan.copy(alpha = 0.25f)
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = message.text,
                color = JarvisText,
                fontSize = 15.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun MicButton(state: JarvisState, listening: Boolean, onClick: () -> Unit) {
    val accent = stateAccent(state)
    Button(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier.size(88.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = JarvisBackground
        )
    ) {
        Text(
            text = if (listening) "PAUSE" else "MIC",
            fontWeight = FontWeight.Bold
        )
    }
}
