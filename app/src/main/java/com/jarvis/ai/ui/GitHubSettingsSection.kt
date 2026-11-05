package com.jarvis.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.security.GitHubStore
import com.jarvis.ai.ui.theme.JarvisBackground
import com.jarvis.ai.ui.theme.JarvisCyan
import com.jarvis.ai.ui.theme.JarvisError
import com.jarvis.ai.ui.theme.JarvisMuted
import com.jarvis.ai.ui.theme.JarvisText

/** GitHub repo and token for "ऐप बनाओ". Stored encrypted on this phone, never shown again after saving. */
@Composable
fun GitHubSection() {
    val context = LocalContext.current
    val store = remember { GitHubStore(context) }
    var repo by remember { mutableStateOf(store.repo().orEmpty()) }
    var token by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(store.isConfigured()) }
    var message by remember { mutableStateOf("") }

    Text("GitHub (ऐप बनाने के लिए)", color = JarvisCyan, fontSize = 13.sp)
    Text(
        text = if (saved) "Repo: ${store.repo()} (token saved)" else "Repo aur token abhi set nahi hain",
        color = if (saved) JarvisCyan else JarvisMuted,
        fontSize = 13.sp
    )
    OutlinedTextField(
        value = repo,
        onValueChange = { repo = it },
        label = { Text("owner/repo, jaise amleshbela111-cyber/jarvis-apps") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("GitHub token (sirf is repo ke liye)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    OpenLinkButton("GitHub token yahan se banayein", SettingsLinks.GITHUB_TOKEN)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                val cleanRepo = repo.trim()
                val cleanToken = token.trim()
                when {
                    !cleanRepo.matches(Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) ->
                        message = "Repo 'owner/repo' jaisa hona chahiye."
                    cleanToken.length < 20 -> message = "Token bahut chhota lag raha hai."
                    else -> {
                        store.save(cleanRepo, cleanToken)
                        token = ""
                        saved = true
                        message = "GitHub settings save ho gayi."
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = JarvisBackground)
        ) {
            Text("Save GitHub")
        }
        OutlinedButton(
            onClick = {
                store.clear()
                repo = ""
                token = ""
                saved = false
                message = "GitHub settings hata di."
            },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisError)
        ) {
            Text("Remove")
        }
    }
    if (message.isNotEmpty()) {
        Text(text = message, color = JarvisText, fontSize = 12.sp)
    }
}
