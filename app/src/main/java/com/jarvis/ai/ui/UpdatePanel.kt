package com.jarvis.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jarvis.ai.ui.theme.JarvisBackground
import com.jarvis.ai.ui.theme.JarvisCyan
import com.jarvis.ai.ui.theme.JarvisMuted
import com.jarvis.ai.update.UpdateFlow

/**
 * Prompt and Termux box. Type or paste here, then send the prompt to Claude or the code to Termux.
 * When Jarvis opens and the clipboard holds a Termux command, it fills the box automatically.
 */
@Composable
fun UpdatePanel() {
    val context = LocalContext.current
    val draft by UpdateFlow.draft.collectAsState()
    var text by remember { mutableStateOf(draft) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(draft) { text = draft }

    // Fills the box with a Termux command from the clipboard, when one is there and the box is empty.
    fun fillFromClipboard() {
        val clip = UpdateFlow.clipboardText(context) ?: return
        val looksLikeTermux = clip.trimStart().startsWith("set -e") ||
            (clip.contains("git push") && clip.contains("JarvisAI"))
        if (looksLikeTermux && text.isBlank()) {
            text = clip
            UpdateFlow.draft.value = clip
            note = "Termux ka code mila. TERMUX button dabaiye."
        }
    }

    // The panel appears only after "update Jarvis", so check the clipboard when it opens and on each resume.
    LaunchedEffect(Unit) { fillFromClipboard() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { fillFromClipboard() }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                UpdateFlow.draft.value = it
                note = ""
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 140.dp),
            label = { Text("Prompt ya Termux code yahan paste karein") },
            maxLines = 6
        )
        if (note.isNotEmpty()) {
            Text(text = note, color = JarvisMuted, fontSize = 12.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    UpdateFlow.copy(context, text)
                    UpdateFlow.openClaude(context)
                },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
            ) {
                Text("CLAUDE")
            }
            Button(
                onClick = {
                    UpdateFlow.copy(context, text)
                    UpdateFlow.openTermux(context)
                },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
            ) {
                Text("TERMUX")
            }
            OutlinedButton(onClick = { UpdateFlow.openGithubActions(context) }) {
                Text("GITHUB")
            }
        }
        TextButton(onClick = { UpdateFlow.panelVisible.value = false }) {
            Text("BAND KARO", color = JarvisMuted)
        }
    }
}
