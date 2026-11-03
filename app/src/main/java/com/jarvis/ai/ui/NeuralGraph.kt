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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.jarvis.ai.model.JarvisState
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val NODE_COUNT = 36
private const val NEAREST_LINKS = 3
private const val CAMERA_DISTANCE = 2.4f

private class GraphNode(val x: Float, val y: Float, val z: Float, val color: Color)

private val NodeColors = listOf(
    Color(0xFFFF4FD8), // pink
    Color(0xFFB84DFF), // purple
    Color(0xFFFF9500), // orange
    Color(0xFFFFD27A), // gold
    Color(0xFFFFFFFF)  // white
)

/** Builds the random-but-fixed 3D network once: nodes inside a sphere, and each node linked to its nearest neighbours. */
private data class Network(val nodes: List<GraphNode>, val edges: List<Pair<Int, Int>>)

private val network: Network by lazy { buildNetwork() }

private fun buildNetwork(): Network {
    val rnd = Random(7)
    val nodes = List(NODE_COUNT) {
        var x: Float
        var y: Float
        var z: Float
        do {
            x = rnd.nextFloat() * 2f - 1f
            y = rnd.nextFloat() * 2f - 1f
            z = rnd.nextFloat() * 2f - 1f
        } while (x * x + y * y + z * z > 1f)
        GraphNode(x * 0.9f, y * 0.9f, z * 0.9f, NodeColors[rnd.nextInt(NodeColors.size)])
    }
    val links = mutableSetOf<Pair<Int, Int>>()
    for (i in nodes.indices) {
        val nearest = nodes.indices
            .filter { it != i }
            .sortedBy { j -> squaredDistance(nodes[i], nodes[j]) }
            .take(NEAREST_LINKS)
        for (j in nearest) links += if (i < j) i to j else j to i
    }
    return Network(nodes, links.toList())
}

private fun squaredDistance(a: GraphNode, b: GraphNode): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return dx * dx + dy * dy + dz * dz
}

/**
 * A 3D neural network that turns slowly. Light pulses run along the links, and the glow grows with the voice.
 * The colour follows the state, as in the arc reactor.
 */
@Composable
fun NeuralGraph(state: JarvisState, soundLevel: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "neural-graph")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 30000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )
    val breath by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath"
    )

    val accent = stateAccent(state)
    val level = if (state == JarvisState.LISTENING || state == JarvisState.SPEAKING) {
        soundLevel.coerceIn(0f, 1f)
    } else {
        0f
    }

    Canvas(modifier = modifier.size(280.dp)) {
        drawNetwork(network, spin, pulse, accent, breath * (1f + level * 0.5f))
    }
}

private fun DrawScope.drawNetwork(net: Network, spinDeg: Float, pulse: Float, accent: Color, glow: Float) {
    val c = center
    val r = size.minDimension / 2f * 0.92f
    val yaw = Math.toRadians(spinDeg.toDouble())
    val tilt = Math.toRadians(-18.0)
    val cosY = cos(yaw).toFloat()
    val sinY = sin(yaw).toFloat()
    val cosT = cos(tilt).toFloat()
    val sinT = sin(tilt).toFloat()

    // Rotate every node around the vertical axis, then tilt, then project with simple perspective.
    val count = net.nodes.size
    val px = FloatArray(count)
    val py = FloatArray(count)
    val pz = FloatArray(count)
    val ps = FloatArray(count)
    for (i in 0 until count) {
        val n = net.nodes[i]
        val x1 = n.x * cosY + n.z * sinY
        val z1 = -n.x * sinY + n.z * cosY
        val y1 = n.y * cosT - z1 * sinT
        val z2 = n.y * sinT + z1 * cosT
        val persp = CAMERA_DISTANCE / (CAMERA_DISTANCE + z2)
        px[i] = c.x + x1 * persp * r
        py[i] = c.y + y1 * persp * r
        pz[i] = z2
        ps[i] = persp
    }

    // Links: the farther ones are dimmer.
    for ((a, b) in net.edges) {
        val depth = (pz[a] + pz[b]) / 2f
        val alpha = (0.12f + 0.28f * (depth + 1f) / 2f).coerceIn(0.05f, 0.6f)
        drawLine(
            color = accent.copy(alpha = alpha),
            start = Offset(px[a], py[a]),
            end = Offset(px[b], py[b]),
            strokeWidth = r * 0.008f
        )
    }

    // Light packets that run along the links.
    net.edges.forEachIndexed { k, (a, b) ->
        if (k % 3 == 0) {
            val t = (pulse + k * 0.37f) % 1f
            val x = px[a] + (px[b] - px[a]) * t
            val y = py[a] + (py[b] - py[a]) * t
            drawCircle(color = accent, radius = r * 0.014f, center = Offset(x, y), blendMode = BlendMode.Plus)
        }
    }

    // Nodes: far ones first, so near ones sit on top.
    val order = (0 until count).sortedBy { pz[it] }
    for (i in order) {
        val node = net.nodes[i]
        val center = Offset(px[i], py[i])
        val radius = r * 0.035f * ps[i] * glow
        drawCircle(
            color = node.color.copy(alpha = 0.28f),
            radius = radius * 3f,
            center = center,
            blendMode = BlendMode.Plus
        )
        drawCircle(color = node.color, radius = radius, center = center)
        drawCircle(color = Color.White.copy(alpha = 0.9f), radius = radius * 0.4f, center = center)
    }
}

