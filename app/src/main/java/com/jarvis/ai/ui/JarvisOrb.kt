package com.jarvis.ai.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.jarvis.ai.model.JarvisState
import com.jarvis.ai.ui.theme.JarvisBackground
import com.jarvis.ai.ui.theme.JarvisBlue
import com.jarvis.ai.ui.theme.JarvisCyan
import com.jarvis.ai.ui.theme.JarvisError
import com.jarvis.ai.ui.theme.JarvisSky

/** Accent colour for each assistant state. Shared by the orb and the mic button. */
fun stateAccent(state: JarvisState): Color = when (state) {
    JarvisState.IDLE -> JarvisBlue
    JarvisState.LISTENING -> JarvisCyan
    JarvisState.THINKING -> JarvisBlue
    JarvisState.EXECUTING -> JarvisSky
    JarvisState.SPEAKING -> JarvisCyan
    JarvisState.ERROR -> JarvisError
}

/**
 * Animated central orb. It pulses slowly when idle, follows the microphone
 * level while listening, spins its rings faster while thinking, and glows
 * while speaking.
 */
@Composable
fun JarvisOrb(
    state: JarvisState,
    soundLevel: Float,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "jarvis-orb")

    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb-pulse"
    )

    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb-spin"
    )

    val accent = stateAccent(state)

    val activity = when (state) {
        JarvisState.LISTENING -> 0.25f + soundLevel * 0.75f
        JarvisState.SPEAKING -> 0.6f
        JarvisState.THINKING, JarvisState.EXECUTING -> 0.45f
        JarvisState.IDLE, JarvisState.ERROR -> 0.1f
    }

    Canvas(modifier = modifier.size(260.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val base = size.minDimension / 2f
        val radiusScale = (0.78f + 0.10f * pulse + 0.12f * activity)

        // Outer glow
        val glowRadius = base * radiusScale * 1.2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.35f), Color.Transparent),
                center = center,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = center
        )

        // Core
        val coreRadius = base * 0.42f * radiusScale
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.85f), accent, JarvisBackground),
                center = center,
                radius = coreRadius
            ),
            radius = coreRadius,
            center = center
        )

        // Three rotating arc rings
        for (i in 0 until 3) {
            val r = base * (0.60f + 0.12f * i) * radiusScale
            val direction = if (i % 2 == 0) 1f else -1.5f
            drawArc(
                color = accent.copy(alpha = 0.85f - 0.2f * i),
                startAngle = spin * direction + i * 120f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}
