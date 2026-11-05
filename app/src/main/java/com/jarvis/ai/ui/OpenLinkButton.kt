package com.jarvis.ai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.jarvis.ai.ui.theme.JarvisCyan

/** Opens the page where the key, token or model is made, in the phone's browser (Chrome, if it is the default). */
@Composable
fun OpenLinkButton(text: String, url: String) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan)
    ) {
        Text(text)
    }
}

/** Pages where each key or model is created. */
object SettingsLinks {
    const val GEMINI_KEY = "https://aistudio.google.com/app/apikey"
    const val GEMINI_MODELS = "https://ai.google.dev/gemini-api/docs/models"
    const val GROQ_KEY = "https://console.groq.com/keys"
    const val GITHUB_TOKEN = "https://github.com/settings/personal-access-tokens/new"
    const val VOSK_MODELS = "https://alphacephei.com/vosk/models"
}
