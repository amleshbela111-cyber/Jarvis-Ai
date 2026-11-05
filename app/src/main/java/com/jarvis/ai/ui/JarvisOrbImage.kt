package com.jarvis.ai.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.jarvis.ai.R
import com.jarvis.ai.model.JarvisState

/**
 * The Jarvis orb, shown with the supplied orb image exactly as it is.
 * The image itself is never redrawn. Only its size breathes: slowly when idle, faster while
 * listening, thinking or speaking. Tapping it still toggles listening.
 */
@Composable
fun JarvisOrbImage(
    state: JarvisState,
    soundLevel: Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {}
) {
    val (amplitude, periodMs) = when (state) {
        JarvisState.LISTENING -> 0.05f to 500
        JarvisState.THINKING -> 0.03f to 900
        JarvisState.SPEAKING -> 0.035f to 350
        JarvisState.ERROR -> 0.02f to 700
        else -> 0.01f to 2400
    }
    // One full turn of the orb: slow when idle, faster while listening or thinking.
    val turnMs = when (state) {
        JarvisState.LISTENING -> 14_000
        JarvisState.THINKING -> 10_000
        JarvisState.SPEAKING -> 12_000
        else -> 40_000
    }
    val transition = rememberInfiniteTransition(label = "jarvis-orb")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "jarvis-orb-pulse"
    )
    // While listening, the orb also swells with the voice level.
    val voice = if (state == JarvisState.LISTENING) soundLevel.coerceIn(0f, 1f) * 0.04f else 0f
    val scale = 1f + amplitude * pulse + voice

    // The whole orb turns slowly, so its rings appear to move around.
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = turnMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "jarvis-orb-turn"
    )

    Box(
        modifier = modifier.size(300.dp).clickable(onClick = onTap),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.jarvis_orb),
            contentDescription = "Jarvis orb",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = turn
                }
        )
    }
}
