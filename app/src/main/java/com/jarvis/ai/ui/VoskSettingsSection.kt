package com.jarvis.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.jarvis.ai.ui.theme.JarvisBackground
import com.jarvis.ai.ui.theme.JarvisCyan
import com.jarvis.ai.ui.theme.JarvisMuted
import com.jarvis.ai.voice.VoskModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets the user pick the unzipped Vosk model folders from the phone. They are copied into Jarvis storage. */
@Composable
fun VoskSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var pickingLang by remember { mutableStateOf("en") }
    var enReady by remember { mutableStateOf(VoskModelStore.isReady(context, "en")) }
    var hiReady by remember { mutableStateOf(VoskModelStore.isReady(context, "hi")) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val lang = pickingLang
        status = "Model copy ho raha hai..."
        scope.launch {
            val ok = withContext(Dispatchers.IO) { VoskModelStore.importFolder(context, uri, lang) }
            if (lang == "en") enReady = ok else hiReady = ok
            status = if (ok) {
                "Model save ho gaya."
            } else {
                "Ye folder Vosk model nahi lag raha. Usme 'am' aur 'conf' folder hone chahiye."
            }
        }
    }

    Text("Offline voice (Hey Jarvis)", color = JarvisCyan, fontSize = 13.sp)
    Text(
        text = "English model: ${if (enReady) "saved" else "nahi hai"}  |  Hindi model: ${if (hiReady) "saved" else "nahi hai"}",
        color = JarvisMuted,
        fontSize = 13.sp
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                pickingLang = "en"
                picker.launch(null)
            },
            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
        ) {
            Text("English model folder chuno")
        }
        Button(
            onClick = {
                pickingLang = "hi"
                picker.launch(null)
            },
            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
        ) {
            Text("Hindi model folder chuno")
        }
    }
    if (status.isNotEmpty()) {
        Text(text = status, color = JarvisMuted, fontSize = 12.sp)
    }
}
