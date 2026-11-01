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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.jarvis.ai.model.JarvisState
import kotlin.math.cos
import kotlin.math.sin

private val Gold = Color(0xFFFFD27A)
private val Amber = Color(0xFFFF9500)
private val Ember = Color(0xFFFF5A00)
private val Alarm = Color(0xFFFF3B30)

/** Main colour of the reactor for each state. */
fun stateAccent(state: JarvisState): Color = when (state) {
    JarvisState.IDLE -> Amber
    JarvisState.LISTENING -> Gold
    JarvisState.THINKING -> Ember
    JarvisState.EXECUTING -> Ember
    JarvisState.SPEAKING -> Gold
    JarvisState.ERROR -> Alarm
}

/**
 * Arc-reactor style orb: glowing core, counter-rotating rings, and a rotating star.
 * It keeps moving in every state. Thinking spins faster, and listening or speaking pulses with the voice level.
 */
@Composable
fun JarvisOrb(state: JarvisState, soundLevel: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "arc-reactor")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 16000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val spinFast by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 4000, easing = LinearEasing), RepeatMode.Restart),
        label = "spinFast"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    val accent = stateAccent(state)
    val busy = state == JarvisState.THINKING || state == JarvisState.EXECUTING
    val level = if (state == JarvisState.LISTENING || state == JarvisState.SPEAKING) soundLevel.coerceIn(0f, 1f) else 0f
    val ringSpin = if (busy) spinFast else spin

    Canvas(modifier = modifier.size(280.dp)) {
        drawArcReactor(accent = accent, ringSpin = ringSpin, slowSpin = spin, pulse = pulse, level = level)
    }
}

private fun DrawScope.drawArcReactor(
    accent: Color,
    ringSpin: Float,
    slowSpin: Float,
    pulse: Float,
    level: Float
) {
    val c = center
    val r = size.minDimension / 2f

    // Soft outer glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.45f * pulse), Color.Transparent),
            center = c,
            radius = r
        ),
        radius = r,
        center = c
    )

    // Thin outer ring
    drawCircle(color = accent.copy(alpha = 0.6f), radius = r * 0.95f, center = c, style = Stroke(width = 2.dp.toPx()))

    // Outer segmented ring with bright nodes, spinning clockwise
    rotate(degrees = ringSpin, pivot = c) {
        for (i in 0 until 12) {
            drawArc(
                color = accent,
                startAngle = i * 30f,
                sweepAngle = 18f,
                useCenter = false,
                topLeft = Offset(c.x - r * 0.84f, c.y - r * 0.84f),
                size = Size(r * 1.68f, r * 1.68f),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            )
            drawCircle(color = Gold, radius = 3.dp.toPx(), center = polar(c, r * 0.84f, i * 30f))
        }
    }

    // Middle ring of thick arcs, spinning counter-clockwise
    rotate(degrees = -slowSpin * 0.7f, pivot = c) {
        for (i in 0 until 4) {
            drawArc(
                color = accent.copy(alpha = 0.9f),
                startAngle = i * 90f + 10f,
                sweepAngle = 60f,
                useCenter = false,
                topLeft = Offset(c.x - r * 0.62f, c.y - r * 0.62f),
                size = Size(r * 1.24f, r * 1.24f),
                style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Butt)
            )
        }
    }

    // Inner star (two triangles) and its guide ring
    drawCircle(color = accent.copy(alpha = 0.5f), radius = r * 0.44f, center = c, style = Stroke(width = 1.5.dp.toPx()))
    rotate(degrees = slowSpin * 1.2f, pivot = c) {
        drawPath(triangle(c, r * 0.44f, 270f), color = accent, style = Stroke(width = 2.dp.toPx()))
        drawPath(triangle(c, r * 0.44f, 90f), color = accent, style = Stroke(width = 2.dp.toPx()))
    }

    // Glowing core, which grows with the voice level
    val coreRadius = r * 0.2f * pulse * (1f + level * 0.6f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.8f), Color.Transparent),
            center = c,
            radius = r * 0.5f
        ),
        radius = r * 0.5f,
        center = c
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, Gold, Ember),
            center = c,
            radius = coreRadius
        ),
        radius = coreRadius,
        center = c
    )
    drawCircle(color = accent, radius = coreRadius * 1.25f, center = c, style = Stroke(width = 2.dp.toPx()))
}

private fun polar(center: Offset, radius: Float, degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    return Offset(center.x + radius * cos(rad).toFloat(), center.y + radius * sin(rad).toFloat())
}

private fun triangle(center: Offset, radius: Float, startDegrees: Float): Path = Path().apply {
    for (k in 0 until 3) {
        val p = polar(center, radius, startDegrees + k * 120f)
        if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}
