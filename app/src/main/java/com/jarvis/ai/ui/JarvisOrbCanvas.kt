package com.jarvis.ai.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.jarvis.ai.model.JarvisState
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Jarvis orb, drawn in code. Layers: metal segment rings, glowing strips, tick rings,
 * hairline rings, a bright core, and crosshair light. Each moving layer turns on its own
 * speed and direction. Radii are measured on a 1254 px reference, then scaled to the screen.
 */
private const val REF = 1254f
private const val ORB_CENTER_Y_OFFSET = -37f

private val Ember = Color(0xFFFF8A00)
private val Gold = Color(0xFFFFD040)
private val Hot = Color(0xFFFFF2B0)
private val Steel = Color(0xFF2A2A2E)
private val SteelLight = Color(0xFFB8B8C0)

@Composable
fun JarvisOrbCanvas(
    state: JarvisState,
    soundLevel: Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {}
) {
    // Speed factor: lower is faster. Listening and thinking make the orb move quicker.
    val speed = when (state) {
        JarvisState.LISTENING -> 0.5f
        JarvisState.THINKING -> 0.7f
        JarvisState.SPEAKING -> 0.8f
        else -> 1f
    }
    val pulseMs = when (state) {
        JarvisState.LISTENING -> 500
        JarvisState.SPEAKING -> 350
        JarvisState.THINKING -> 900
        else -> 2400
    }
    val t = rememberInfiniteTransition(label = "jarvis-orb-canvas")

    @Composable
    fun turn(baseMs: Int, clockwise: Boolean): State<Float> = t.animateFloat(
        initialValue = 0f,
        targetValue = if (clockwise) 360f else -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (baseMs * speed).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb-turn"
    )

    val turnOuter by turn(60_000, clockwise = true)
    val turnMiddle by turn(45_000, clockwise = false)
    val turnInner by turn(30_000, clockwise = true)
    val turnTicks by turn(80_000, clockwise = false)

    val pulse by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = pulseMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb-pulse"
    )
    val voice = if (state == JarvisState.LISTENING) soundLevel.coerceIn(0f, 1f) * 0.05f else 0f
    val glow = 1f + pulse * 0.05f + voice

    Canvas(modifier = modifier.size(300.dp).clickable(onClick = onTap)) {
        val k = size.minDimension / REF
        val c = Offset(size.width / 2f, size.height / 2f + ORB_CENTER_Y_OFFSET * k)

        // Hairline rings, still.
        for (radius in listOf(100f, 124f, 150f, 181f, 208f, 243f, 267f, 300f, 331f, 355f, 380f, 407f, 455f, 480f, 505f)) {
            drawCircle(
                color = Ember,
                radius = radius * k,
                center = c,
                alpha = 0.45f,
                style = Stroke(width = 1.2f * k)
            )
        }

        // Inner tick ring and outer tick ring, turning in opposite directions.
        withTransform({ rotate(turnTicks, c) }) {
            drawTicks(c, 205f * k, 240f * k, 60, k)
        }
        withTransform({ rotate(-turnTicks * 0.6f, c) }) {
            drawTicks(c, 392f * k, 418f * k, 48, k)
        }

        // Three metal segment rings, each with its own speed and direction.
        withTransform({ rotate(turnInner, c) }) {
            drawSegments(c, 165f * k, 30f * k, count = 8, sweep = 28f, glowEvery = 2, k = k)
        }
        withTransform({ rotate(turnMiddle, c) }) {
            drawSegments(c, 315f * k, 30f * k, count = 12, sweep = 20f, glowEvery = 3, k = k)
        }
        withTransform({ rotate(turnOuter, c) }) {
            drawSegments(c, 445f * k, 26f * k, count = 8, sweep = 32f, glowEvery = 3, k = k)
        }

        // Four small bracket blocks around the core, turning with the inner ring.
        withTransform({ rotate(turnInner, c) }) {
            for (angle in listOf(45f, 135f, 225f, 315f)) {
                val rad = 118f * k
                val topLeft = Offset(c.x - rad, c.y - rad)
                val arcSize = Size(rad * 2f, rad * 2f)
                drawArc(color = Steel, startAngle = angle - 11f, sweepAngle = 22f, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(width = 14f * k))
                drawArc(color = SteelLight, startAngle = angle - 9f, sweepAngle = 18f, useCenter = false,
                    topLeft = topLeft, size = arcSize, alpha = 0.5f, style = Stroke(width = 1.5f * k))
                drawArc(color = Gold, startAngle = angle - 8f, sweepAngle = 16f, useCenter = false,
                    topLeft = topLeft, size = arcSize, alpha = 0.9f, style = Stroke(width = 2f * k))
            }
        }

        // Thin inner rings inside the core.
        for (radius in listOf(58f, 74f, 86f)) {
            drawCircle(color = Gold, radius = radius * k * glow, center = c, alpha = 0.55f, style = Stroke(width = 1.4f * k))
        }

        // Crosshair light and the dots on it.
        val reach = 520f * k
        drawLine(Ember, Offset(c.x - reach, c.y), Offset(c.x + reach, c.y), strokeWidth = 1.5f * k, alpha = 0.7f)
        drawLine(Ember, Offset(c.x, c.y - reach), Offset(c.x, c.y + reach), strokeWidth = 1.5f * k, alpha = 0.7f)
        for (angle in listOf(0f, 90f, 180f, 270f)) {
            val p = polar(c, 505f * k, angle)
            drawCircle(color = Gold, radius = 4f * k, center = p)
        }

        // Thin star (two triangles) inside the core ring, as in the reference.
        val starR = 130f * k
        for (base in listOf(90f, 270f)) {
            val p0 = polar(c, starR, base)
            val p1 = polar(c, starR, base + 120f)
            val p2 = polar(c, starR, base + 240f)
            drawLine(Ember, p0, p1, strokeWidth = 1.2f * k, alpha = 0.6f)
            drawLine(Ember, p1, p2, strokeWidth = 1.2f * k, alpha = 0.6f)
            drawLine(Ember, p2, p0, strokeWidth = 1.2f * k, alpha = 0.6f)
        }

        // The bright core, which breathes with the state.
        val coreR = 100f * k * glow
        // Solid gold disc with an ember ring at the centre, as in the reference.
        drawCircle(color = Gold, radius = 34f * k * glow, center = c, alpha = 0.9f)
        drawCircle(color = Ember, radius = 40f * k * glow, center = c, alpha = 0.8f, style = Stroke(width = 3f * k))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Hot, Gold, Ember, Color.Transparent),
                center = c,
                radius = coreR
            ),
            radius = coreR,
            center = c
        )
    }
}

/** A ring of metal arcs. Some carry an orange glow strip. */
private fun DrawScope.drawSegments(
    c: Offset,
    radius: Float,
    thickness: Float,
    count: Int,
    sweep: Float,
    glowEvery: Int,
    k: Float
) {
    val topLeft = Offset(c.x - radius, c.y - radius)
    val arcSize = Size(radius * 2f, radius * 2f)
    val step = 360f / count
    for (i in 0 until count) {
        val start = i * step - sweep / 2f
        drawArc(
            color = Steel,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = thickness)
        )
        drawArc(
            color = SteelLight,
            startAngle = start + sweep * 0.1f,
            sweepAngle = sweep * 0.8f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            alpha = 0.35f,
            style = Stroke(width = 1.5f * k)
        )
        if (i % glowEvery == 0) {
            drawArc(
                color = Ember,
                startAngle = start + sweep * 0.15f,
                sweepAngle = sweep * 0.7f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                alpha = 0.3f,
                style = Stroke(width = thickness * 0.7f)
            )
            drawArc(
                color = Gold,
                startAngle = start + sweep * 0.15f,
                sweepAngle = sweep * 0.7f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = thickness * 0.2f)
            )
        }
    }
}

/** Short tick marks between two radii. */
private fun DrawScope.drawTicks(c: Offset, inner: Float, outer: Float, count: Int, k: Float) {
    val step = 360f / count
    for (i in 0 until count) {
        val angle = i * step
        drawLine(
            color = Ember,
            start = polar(c, inner, angle),
            end = polar(c, outer, angle),
            strokeWidth = 2f * k,
            alpha = 0.8f
        )
    }
}

private fun polar(c: Offset, radius: Float, degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    return Offset(c.x + (radius * cos(rad)).toFloat(), c.y + (radius * sin(rad)).toFloat())
}
