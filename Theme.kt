package com.jarvis.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val JarvisBackground = Color(0xFF05080F)
val JarvisSurface = Color(0xFF0C1322)
val JarvisCyan = Color(0xFF00E5FF)
val JarvisBlue = Color(0xFF2979FF)
val JarvisSky = Color(0xFF40C4FF)
val JarvisText = Color(0xFFE0F7FA)
val JarvisMuted = Color(0xFF6B8EA3)
val JarvisError = Color(0xFFFF5252)

private val JarvisColorScheme = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = JarvisBackground,
    secondary = JarvisBlue,
    background = JarvisBackground,
    onBackground = JarvisText,
    surface = JarvisSurface,
    onSurface = JarvisText,
    error = JarvisError
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisColorScheme,
        content = content
    )
}
