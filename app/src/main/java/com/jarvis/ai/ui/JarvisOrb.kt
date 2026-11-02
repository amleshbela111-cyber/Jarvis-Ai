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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
 * Arc reactor with several layers: a glowing bloom, a sweep-lit bezel, tick marks, a dashed ring,
 * two counter-rotating segmented rings, a scanning light, a rotating hexagon, a glowing star and a bright core.
 * Everything keeps moving. Thinking spins faster, and listening or speaking pushes energy out with the voice.
 */
@Composable
fun JarvisOrb(state: JarvisState, soundLevel: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "arc-reactor")
    val slow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 24000, easing = LinearEasing), RepeatMode.Restart),
        label = "slow"
    )
    val fast by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 5000, easing = LinearEasing), RepeatMode.Restart),
        label = "fast"
    )
    val scan by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 3200, easing = LinearEasing), RepeatMode.Restart),
        label = "scan"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    val accent = stateAccent(state)
    val busy = state == JarvisState.THINKING || state == JarvisState.EXECUTING
    val level = if (state == JarvisState.LISTENING || state == JarvisState.SPEAKING) {
        soundLevel.coerceIn(0f, 1f)
    } else {
        0f
    }

    Canvas(modifier = modifier.size(280.dp)) {
        drawReactor(
            accent = accent,
            spin = if (busy) fast else slow,
            slowSpin = slow,
            scanAngle = scan,
            pulse = pulse,
            level = level
        )
    }
}

private fun DrawScope.drawReactor(
    accent: Color,
    spin: Float,
    slowSpin: Float,
    scanAngle: Float,
    pulse: Float,
    level: Float
) {
    val c = center
    val r = size.minDimension / 2f
    val thin = r * 0.012f
    val energy = pulse * (1f + level * 0.35f)

    // 1. Bloom behind everything
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.40f * energy), accent.copy(alpha = 0.10f), Color.Transparent),
            center = c,
            radius = r
        ),
        radius = r,
        center = c,
        blendMode = BlendMode.Plus
    )

    // 2. Outer bezel with a sweep of light around it
    drawCircle(
        brush = Brush.sweepGradient(listOf(Ember, Gold, Amber, Ember, Gold, Amber, Ember), c),
        radius = r * 0.97f,
        center = c,
        style = Stroke(width = r * 0.035f)
    )

    // 3. Tick marks, every 5 degrees, with longer marks every 30 degrees
    rotate(degrees = slowSpin * 0.25f, pivot = c) {
        for (i in 0 until 72) {
            val angle = i * 5f
            val major = i % 6 == 0
            val start = polar(c, r * (if (major) 0.86f else 0.92f), angle)
            val end = polar(c, r * 0.955f, angle)
            drawLine(
                color = accent.copy(alpha = if (major) 0.95f else 0.5f),
                start = start,
                end = end,
                strokeWidth = if (major) thin * 2.2f else thin
            )
        }
    }

    // 4. Dashed ring
    drawCircle(
        color = accent.copy(alpha = 0.7f),
        radius = r * 0.82f,
        center = c,
        style = Stroke(
            width = thin * 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.05f, r * 0.03f))
        )
    )

    // 5. Two segmented rings, turning in opposite directions
    rotate(degrees = spin, pivot = c) {
        segmentedRing(c, r * 0.74f, 16, accent, thin * 5f)
    }
    rotate(degrees = -slowSpin * 0.6f, pivot = c) {
        segmentedRing(c, r * 0.62f, 8, Gold, thin * 7f)
    }

    // 6. Scanning light that sweeps around the core
    rotate(degrees = scanAngle, pivot = c) {
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(Color.Transparent, accent.copy(alpha = 0.5f), Color.Transparent),
                c
            ),
            radius = r * 0.7f,
            center = c,
            blendMode = BlendMode.Plus
        )
    }

    // 7. Hexagon frame turning slowly
    rotate(degrees = -slowSpin * 0.4f, pivot = c) {
        drawPath(
            path = polygon(c, r * 0.5f, 6, 0f),
            color = accent.copy(alpha = 0.7f),
            style = Stroke(width = thin * 2f)
        )
    }

    // 8. Glowing star (two triangles)
    rotate(degrees = slowSpin * 1.1f, pivot = c) {
        for (start in listOf(-90f, 90f)) {
            val tri = polygon(c, r * 0.42f, 3, start)
            drawPath(path = tri, color = accent.copy(alpha = 0.25f), style = Stroke(width = thin * 6f))
            drawPath(path = tri, color = accent, style = Stroke(width = thin * 2f))
        }
    }

    // 9. Core with layered glow
    val core = r * 0.16f * energy
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.9f), Color.Transparent),
            center = c,
            radius = r * 0.42f
        ),
        radius = r * 0.42f,
        center = c,
        blendMode = BlendMode.Plus
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, Gold, Color.Transparent),
            center = c,
            radius = core * 1.6f
        ),
        radius = core * 1.6f,
        center = c
    )
    drawCircle(color = Color.White, radius = core * 0.45f, center = c)

    // 10. Energy ring that expands with the voice
    if (level > 0f) {
        drawCircle(
            color = accent.copy(alpha = 0.6f * level),
            radius = r * (0.5f + 0.4f * level),
            center = c,
            style = Stroke(width = thin * 2f)
        )
    }
}

private fun DrawScope.segmentedRing(center: Offset, radius: Float, segments: Int, color: Color, width: Float) {
    val step = 360f / segments
    for (i in 0 until segments) {
        drawArc(
            color = color,
            startAngle = i * step,
            sweepAngle = step * 0.6f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = width, cap = StrokeCap.Round)
        )
    }
}

private fun polar(center: Offset, radius: Float, degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    return Offset(center.x + radius * cos(rad).toFloat(), center.y + radius * sin(rad).toFloat())
}

private fun polygon(center: Offset, radius: Float, sides: Int, startDegrees: Float): Path = Path().apply {
    for (k in 0 until sides) {
        val p = polar(center, radius, startDegrees + k * 360f / sides)
        if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}
