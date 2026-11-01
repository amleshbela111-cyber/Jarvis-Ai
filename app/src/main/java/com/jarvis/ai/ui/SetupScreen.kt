package com.jarvis.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.ui.theme.JarvisBackground
import com.jarvis.ai.ui.theme.JarvisCyan
import com.jarvis.ai.ui.theme.JarvisMuted
import com.jarvis.ai.ui.theme.JarvisText
import com.jarvis.ai.viewmodel.JarvisUiState

/**
 * One place to set up every permission Jarvis needs. Each row opens the exact Android page.
 * Android requires the user to allow these; the app cannot grant them by itself.
 */
@Composable
fun SetupScreen(
    ui: JarvisUiState,
    onBack: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onEnableDeviceAdmin: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenMicPermission: () -> Unit
) {
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
            Text(text = "SETUP", color = JarvisCyan, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        Text(
            text = "Ye sab ek baar allow karein. Har row par tap karne se sahi Android page khulega. " +
                "Ye permissions Android khud deta hai, Jarvis inhe apne aap allow nahi kar sakta.",
            color = JarvisMuted,
            fontSize = 13.sp
        )

        SetupRow("Microphone (sunne ke liye)", ui.micPermissionGranted, "Allow", onOpenMicPermission)
        SetupRow("Accessibility (tap, scroll, type, screen padhna)", ui.accessibilityOn, "Open", onOpenAccessibility)
        SetupRow("Screen reading (screen ke sawal)", ui.screenCaptureOn, "Allow", onRequestScreenCapture)
        SetupRow("Modify system settings (brightness)", ui.canWriteSettings, "Open", onOpenWriteSettings)
        SetupRow("Device admin (screen lock)", ui.deviceAdminActive, "Enable", onEnableDeviceAdmin)
        SetupRow("Notifications (listening ka notification dikhane ke liye)", null, "Open", onOpenNotifications)
        SetupRow("Battery (background mein band na ho)", null, "Open", onOpenBattery)
    }
}

@Composable
private fun SetupRow(label: String, granted: Boolean?, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = JarvisText)
            val status = when (granted) {
                true -> "Done"
                false -> "Pending"
                null -> ""
            }
            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    color = if (granted == true) JarvisCyan else JarvisMuted,
                    fontSize = 13.sp
                )
            }
        }
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
        ) {
            Text(actionLabel)
        }
    }
}
