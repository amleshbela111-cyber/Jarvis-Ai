package com.jarvis.ai.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.model.JarvisState
import kotlin.math.cos
import kotlin.math.sin

private val HudBlue = Color(0xFF2EC8FF)
private val HudDeep = Color(0xFF0A6CFF)
private val HudWhite = Color(0xFFDFF6FF)
private val HudAlarm = Color(0xFFFF3B30)

/**
 * The main Jarvis visual: blue HUD rings that turn slowly while idle. They grow and turn faster
 * when Jarvis listens or speaks (faster still when thinking), with the voice level driving the change.
 */
@Composable
fun JarvisHud(state: JarvisState, soundLevel: Float, modifier: Modifier = Modifier) {
    val active = state == JarvisState.LISTENING || state == JarvisState.SPEAKING ||
        state == JarvisState.THINKING || state == JarvisState.EXECUTING
    val busy = state == JarvisState.THINKING || state == JarvisState.EXECUTING
    val level = if (state == JarvisState.LISTENING || state == JarvisState.SPEAKING) {
        soundLevel.coerceIn(0f, 1f)
    } else {
        0f
    }
    val color = if (state == JarvisState.ERROR) HudAlarm else HudBlue

    // Degrees per second. Changes are eased, so the speed-up looks smooth.
    val baseSpeed = when {
        busy -> 95f
        active -> 45f
        else -> 9f
    }
    val speed by animateFloatAsState(
        targetValue = baseSpeed + level * 40f,
        animationSpec = tween(durationMillis = 700),
        label = "hud-speed"
    )
    val scale by animateFloatAsState(
        targetValue = if (active) 1.08f + level * 0.12f else 1f,
        animationSpec = tween(durationMillis = 600),
        label = "hud-scale"
    )

    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) angle = (angle + speed * ((now - last) / 1_000_000_000f)) % 360f
                last = now
            }
        }
    }

    Box(modifier = modifier.size(300.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize().scale(scale)) {
            drawHud(angle = angle, level = level, color = color)
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = 22.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "JARVIS",
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = "YOUR AI ASSISTANT",
                color = HudBlue.copy(alpha = 0.85f),
                fontSize = 9.sp,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun DrawScope.drawHud(angle: Float, level: Float, color: Color) {
    val c = center
    val r = size.minDimension / 2f * 0.95f
    val thin = r * 0.008f

    // Soft blue glow behind everything.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(HudDeep.copy(alpha = 0.35f), color.copy(alpha = 0.10f), Color.Transparent),
            center = c,
            radius = r
        ),
        radius = r,
        center = c,
        blendMode = BlendMode.Plus
    )

    // Outer thick segmented ring.
    rotate(degrees = angle * 0.5f, pivot = c) {
        arcRing(c, r * 0.98f, 6, 40f, r * 0.022f, color)
    }
    // Thin outer circle.
    drawCircle(color = color.copy(alpha = 0.5f), radius = r * 0.90f, center = c, style = Stroke(width = thin * 2f))

    // Fine tick marks that turn the other way.
    rotate(degrees = -angle * 0.3f, pivot = c) {
        for (i in 0 until 120) {
            val deg = i * 3f
            val major = i % 10 == 0
            drawLine(
                color = color.copy(alpha = if (major) 0.9f else 0.45f),
                start = polar(c, r * (if (major) 0.84f else 0.87f), deg),
                end = polar(c, r * 0.89f, deg),
                strokeWidth = if (major) thin * 2.5f else thin
            )
        }
    }

    // Dashed ring.
    rotate(degrees = angle * 0.6f, pivot = c) {
        drawCircle(
            color = color.copy(alpha = 0.7f),
            radius = r * 0.78f,
            center = c,
            style = Stroke(
                width = thin * 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.05f, r * 0.03f))
            )
        )
    }

    // Inner segmented ring, turning faster the other way.
    rotate(degrees = -angle * 1.2f, pivot = c) {
        arcRing(c, r * 0.70f, 12, 22f, r * 0.03f, HudDeep)
    }

    // Thin inner circle with a few bright arcs.
    rotate(degrees = angle * 0.8f, pivot = c) {
        arcRing(c, r * 0.60f, 3, 60f, thin * 4f, HudWhite)
    }

    // Core: a glowing visor with two bright eyes. An original design, not a copy of any character.
    val coreW = r * 0.46f * (1f + level * 0.08f)
    val coreH = r * 0.28f * (1f + level * 0.08f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.55f), Color.Transparent),
            center = c,
            radius = r * 0.45f
        ),
        radius = r * 0.45f,
        center = c,
        blendMode = BlendMode.Plus
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(HudWhite, color, HudDeep)),
        topLeft = Offset(c.x - coreW / 2f, c.y - coreH / 2f - r * 0.04f),
        size = Size(coreW, coreH),
        cornerRadius = CornerRadius(coreH / 2f, coreH / 2f)
    )
    val eyeY = c.y - r * 0.04f
    drawCircle(color = Color.White, radius = r * 0.03f, center = Offset(c.x - coreW * 0.22f, eyeY))
    drawCircle(color = Color.White, radius = r * 0.03f, center = Offset(c.x + coreW * 0.22f, eyeY))

    // Voice ring: expands with the sound level.
    if (level > 0f) {
        drawCircle(
            color = color.copy(alpha = 0.6f * level),
            radius = r * (0.5f + 0.25f * level),
            center = c,
            style = Stroke(width = thin * 3f)
        )
    }
}

/** A ring made of [count] arcs, each [sweep] degrees long, with round ends. */
private fun DrawScope.arcRing(center: Offset, radius: Float, count: Int, sweep: Float, width: Float, color: Color) {
    val step = 360f / count
    for (i in 0 until count) {
        drawArc(
            color = color,
            startAngle = i * step,
            sweepAngle = sweep,
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
