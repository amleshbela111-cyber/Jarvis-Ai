package com.jarvis.ai.ui

import android.content.Context
import android.opengl.GLES20 as GL
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.ai.model.JarvisState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * The Jarvis energy core, drawn with native OpenGL ES 2.0. Same shapes, colours and motion as the
 * WebGL version: core, glow halos, orbit rings, inner strands, beams, particles and bloom.
 * Nothing is loaded from the internet.
 */
@Composable
fun JarvisOrbGl(
    state: JarvisState,
    soundLevel: Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {}
) {
    val currentOnTap by rememberUpdatedState(onTap)
    AndroidView(
        modifier = modifier.size(300.dp),
        factory = { ctx ->
            OrbGlView(ctx).apply { onTapCallback = { currentOnTap() } }
        },
        update = { view -> view.setOrbState(state, soundLevel) }
    )
}

class OrbGlView(context: Context) : GLSurfaceView(context) {
    var onTapCallback: (() -> Unit)? = null
    private val renderer = OrbRenderer { mainHandler.post { onTapCallback?.invoke() } }
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        renderer.density = resources.displayMetrics.density
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setOrbState(s: JarvisState, level: Float) {
        renderer.state = s
        renderer.level = level
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Touches outside the orb go to the screen's buttons and text, not to the orb.
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !renderer.isNearOrb(e.x, e.y)) return false
        renderer.onTouch(e.actionMasked, e.x, e.y)
        return true
    }
}

// ---------- matrices (column-major, same maths as the WebGL version) ----------
private object M {
    fun ident(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (c in 0..3) for (r in 0..3) {
            var s = 0f
            for (k in 0..3) s += a[k * 4 + r] * b[c * 4 + k]
            o[c * 4 + r] = s
        }
        return o
    }

    fun persp(fovy: Float, asp: Float, n: Float, f: Float): FloatArray {
        val t = 1f / tan(fovy / 2f)
        val o = FloatArray(16)
        o[0] = t / asp
        o[5] = t
        o[10] = (f + n) / (n - f)
        o[11] = -1f
        o[14] = 2f * f * n / (n - f)
        return o
    }

    fun trans(x: Float, y: Float, z: Float): FloatArray {
        val m = ident(); m[12] = x; m[13] = y; m[14] = z; return m
    }

    fun scl(s: Float): FloatArray {
        val m = ident(); m[0] = s; m[5] = s; m[10] = s; return m
    }

    fun rx(a: Float): FloatArray {
        val c = cos(a); val s = sin(a)
        return floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f)
    }

    fun ry(a: Float): FloatArray {
        val c = cos(a); val s = sin(a)
        return floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
    }

    fun rz(a: Float): FloatArray {
        val c = cos(a); val s = sin(a)
        return floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }

    fun euler(x: Float, y: Float, z: Float): FloatArray = mul(rx(x), mul(ry(y), rz(z)))
}

// ---------- colours (same hex values as the original) ----------
private fun hex(h: Int): FloatArray = floatArrayOf(((h shr 16) and 255) / 255f, ((h shr 8) and 255) / 255f, (h and 255) / 255f)

private val C_ORANGE = hex(0xB8430E)
private val C_ORANGE_DARK = hex(0x7A2A08)
private val C_ORANGE_GLOW = hex(0xD8501A)
private val C_GOLD = hex(0xA8681E)
private val C_CORE = hex(0xB8501A)
private val C_PARTICLE = hex(0xC0581C)
private val C_BODY = hex(0x140701)
private val C_CORE_HOT = hex(0xFFF0C0)

// ---------- shaders (same as the WebGL version) ----------
private const val VS_MESH = """
attribute vec3 aPos;
uniform mat4 uMV;
uniform mat4 uProj;
uniform float uPointSize;
uniform float uPxScale;
varying float vFade;
void main(){
  vec4 p = uMV * vec4(aPos, 1.0);
  gl_Position = uProj * p;
  vFade = clamp(1.0 - (-p.z - 17.0) * 0.3, 0.3, 1.0);
  gl_PointSize = uPointSize * uPxScale / max(-p.z, 0.1);
}
"""

private const val FS_MESH = """
precision mediump float;
uniform vec4 uColor;
uniform float uRound;
varying float vFade;
void main(){
  float a = uColor.a * vFade;
  if (uRound > 0.5) {
    float r = length(gl_PointCoord - 0.5) * 2.0;
    if (r > 1.0) discard;
    a *= 1.0 - smoothstep(0.3, 1.0, r);
  }
  gl_FragColor = vec4(uColor.rgb, a);
}
"""

private const val VS_EMBER = """
attribute vec3 aPos;
attribute float aA;
uniform mat4 uMV;
uniform mat4 uProj;
uniform float uPointSize;
uniform float uPxScale;
varying float vA;
void main(){
  vec4 p = uMV * vec4(aPos, 1.0);
  gl_Position = uProj * p;
  vA = aA * clamp(1.0 - (-p.z - 17.0) * 0.3, 0.3, 1.0);
  gl_PointSize = uPointSize * uPxScale / max(-p.z, 0.1);
}
"""

private const val FS_EMBER = """
precision mediump float;
uniform vec4 uColor;
varying float vA;
void main(){
  float r = length(gl_PointCoord - 0.5) * 2.0;
  if (r > 1.0) discard;
  float a = uColor.a * vA * (1.0 - smoothstep(0.2, 1.0, r));
  if (a <= 0.001) discard;
  gl_FragColor = vec4(uColor.rgb, a);
}
"""

private const val VS_SPRITE = """
attribute vec2 aCorner;
uniform vec3 uCenter;
uniform float uSize;
uniform mat4 uProj;
varying vec2 vC;
void main(){
  vC = aCorner;
  gl_Position = uProj * vec4(uCenter + vec3(aCorner * uSize, 0.0), 1.0);
}
"""

private const val FS_SPRITE = """
precision mediump float;
uniform float uOpacity;
uniform float uHot;
varying vec2 vC;
void main(){
  float d = length(vC);
  if (d > 1.0) discard;
  vec4 s0 = vec4(0.659, 0.298, 0.063, 0.95);
  vec4 s1 = vec4(0.651, 0.263, 0.043, 0.82);
  vec4 s2 = vec4(0.537, 0.192, 0.031, 0.48);
  vec4 s3 = vec4(0.396, 0.141, 0.027, 0.20);
  vec4 s4 = vec4(0.118, 0.031, 0.000, 0.00);
  vec4 c;
  if (d < 0.12) c = mix(s0, s1, d / 0.12);
  else if (d < 0.28) c = mix(s1, s2, (d - 0.12) / 0.16);
  else if (d < 0.52) c = mix(s2, s3, (d - 0.28) / 0.24);
  else c = mix(s3, s4, (d - 0.52) / 0.48);
  float hot = uHot * (1.0 - smoothstep(0.0, 0.4, d));
  c.rgb = mix(c.rgb, vec3(1.0, 0.93, 0.75), hot);
  c.a = max(c.a, hot);
  gl_FragColor = vec4(pow(c.rgb, vec3(2.2)), c.a * uOpacity);
}
"""

private const val VS_FULL = """
attribute vec2 aP;
varying vec2 vUv;
void main(){
  vUv = aP * 0.5 + 0.5;
  gl_Position = vec4(aP, 0.0, 1.0);
}
"""

private const val FS_BLUR = """
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uStep;
uniform float uThresh;
uniform float uUseThresh;
varying vec2 vUv;
vec3 px(float o){
  vec3 c = texture2D(uTex, vUv + uStep * o).rgb;
  if (uUseThresh > 0.5) c = max(c - uThresh, 0.0);
  return c;
}
void main(){
  vec3 c = px(0.0) * 0.2270;
  c += (px(1.0) + px(-1.0)) * 0.1946;
  c += (px(2.0) + px(-2.0)) * 0.1216;
  c += (px(3.0) + px(-3.0)) * 0.0541;
  c += (px(4.0) + px(-4.0)) * 0.0162;
  gl_FragColor = vec4(c, 1.0);
}
"""

private const val FS_COMP = """
precision mediump float;
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform sampler2D uWide;
uniform float uStrength;
uniform float uWideStrength;
varying vec2 vUv;
void main(){
  vec3 c = texture2D(uScene, vUv).rgb + texture2D(uBloom, vUv).rgb * uStrength + texture2D(uWide, vUv).rgb * uWideStrength;
  vec3 e = pow(min(c, 1.0), vec3(0.4545));
  e = max(e - vec3(0.04), 0.0) / 0.96;
  gl_FragColor = vec4(e, 1.0);
}
"""

private class Prog(vs: String, fs: String) {
    val id: Int

    init {
        val v = compile(GL.GL_VERTEX_SHADER, vs)
        val f = compile(GL.GL_FRAGMENT_SHADER, fs)
        id = GL.glCreateProgram()
        GL.glAttachShader(id, v)
        GL.glAttachShader(id, f)
        GL.glLinkProgram(id)
        val ok = IntArray(1)
        GL.glGetProgramiv(id, GL.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException(GL.glGetProgramInfoLog(id))
    }

    fun u(name: String): Int = GL.glGetUniformLocation(id, name)
    fun a(name: String): Int = GL.glGetAttribLocation(id, name)

    private fun compile(type: Int, src: String): Int {
        val s = GL.glCreateShader(type)
        GL.glShaderSource(s, src)
        GL.glCompileShader(s)
        val ok = IntArray(1)
        GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException(GL.glGetShaderInfoLog(s))
        return s
    }
}

private class Geo(val vbo: Int, val count: Int, val ibo: Int = 0, val icount: Int = 0)

private class Ring(val g: Geo, val base: FloatArray, val color: FloatArray, val speed: Float)
private class Strand(val g: Geo, val color: FloatArray, val alpha: Float)
private class Beam(val g: Geo, val color: FloatArray)
private class Target(val fb: Int, val tex: Int, val w: Int, val h: Int)

private fun floatBuf(a: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.position(0) }

private fun makeGeo(verts: FloatArray, idx: IntArray?, count: Int): Geo {
    val b = IntArray(1)
    GL.glGenBuffers(1, b, 0)
    GL.glBindBuffer(GL.GL_ARRAY_BUFFER, b[0])
    GL.glBufferData(GL.GL_ARRAY_BUFFER, verts.size * 4, floatBuf(verts), GL.GL_STATIC_DRAW)
    if (idx == null) return Geo(b[0], count)
    val ib = IntArray(1)
    GL.glGenBuffers(1, ib, 0)
    GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, ib[0])
    val shorts: ShortBuffer = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    for (i in idx) shorts.put(i.toShort())
    shorts.position(0)
    GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, shorts, GL.GL_STATIC_DRAW)
    return Geo(b[0], count, ib[0], idx.size)
}

private fun torusGeo(R: Float, r: Float, radial: Int, tubular: Int, arc: Float): Geo {
    val verts = FloatArray((tubular + 1) * (radial + 1) * 3)
    var k = 0
    for (i in 0..tubular) {
        val u = i.toFloat() / tubular * arc
        for (j in 0..radial) {
            val w = j.toFloat() / radial * 2f * PI.toFloat()
            val rr = R + r * cos(w)
            verts[k++] = rr * cos(u)
            verts[k++] = rr * sin(u)
            verts[k++] = r * sin(w)
        }
    }
    val idx = IntArray(tubular * radial * 6)
    var q = 0
    for (i in 0 until tubular) for (j in 0 until radial) {
        val a = i * (radial + 1) + j
        val b = (i + 1) * (radial + 1) + j
        idx[q++] = a; idx[q++] = b; idx[q++] = a + 1
        idx[q++] = b; idx[q++] = b + 1; idx[q++] = a + 1
    }
    return makeGeo(verts, idx, verts.size / 3)
}

private fun sphereGeo(r: Float, lat: Int, lon: Int): Geo {
    val verts = FloatArray((lat + 1) * (lon + 1) * 3)
    var k = 0
    for (i in 0..lat) {
        val th = i.toFloat() / lat * PI.toFloat()
        for (j in 0..lon) {
            val ph = j.toFloat() / lon * 2f * PI.toFloat()
            verts[k++] = r * sin(th) * cos(ph)
            verts[k++] = r * cos(th)
            verts[k++] = r * sin(th) * sin(ph)
        }
    }
    val idx = IntArray(lat * lon * 6)
    var q = 0
    for (i in 0 until lat) for (j in 0 until lon) {
        val a = i * (lon + 1) + j
        val b = a + lon + 1
        idx[q++] = a; idx[q++] = b; idx[q++] = a + 1
        idx[q++] = b; idx[q++] = b + 1; idx[q++] = a + 1
    }
    return makeGeo(verts, idx, verts.size / 3)
}

private fun cross(a: FloatArray, b: FloatArray): FloatArray =
    floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

private fun norm(a: FloatArray): FloatArray {
    val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).let { if (it == 0f) 1f else it }
    return floatArrayOf(a[0] / l, a[1] / l, a[2] / l)
}

/** A thick tube along a list of points, like three.js TubeGeometry (closed or open). */
private fun tubeGeo(points: List<FloatArray>, closed: Boolean, radius: Float, radial: Int = 6): Geo {
    val n = points.size
    val verts = FloatArray(n * (radial + 1) * 3)
    var k = 0
    for (i in 0 until n) {
        val prev = points[if (i == 0) (if (closed) n - 1 else 0) else i - 1]
        val next = points[if (i == n - 1) (if (closed) 0 else n - 1) else i + 1]
        val t = norm(floatArrayOf(next[0] - prev[0], next[1] - prev[1], next[2] - prev[2]))
        val ref = if (abs(t[1]) > 0.9f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
        val nn = norm(cross(t, ref))
        val bb = cross(t, nn)
        val p = points[i]
        for (j in 0..radial) {
            val a = j.toFloat() / radial * 2f * PI.toFloat()
            val ca = cos(a) * radius
            val sa = sin(a) * radius
            verts[k++] = p[0] + ca * nn[0] + sa * bb[0]
            verts[k++] = p[1] + ca * nn[1] + sa * bb[1]
            verts[k++] = p[2] + ca * nn[2] + sa * bb[2]
        }
    }
    val segs = if (closed) n else n - 1
    val idx = IntArray(segs * radial * 6)
    var q = 0
    for (i in 0 until segs) {
        val i2 = (i + 1) % n
        for (j in 0 until radial) {
            val a = i * (radial + 1) + j
            val b = i2 * (radial + 1) + j
            idx[q++] = a; idx[q++] = b; idx[q++] = a + 1
            idx[q++] = b; idx[q++] = b + 1; idx[q++] = a + 1
        }
    }
    return makeGeo(verts, idx, verts.size / 3)
}

/** Colours are blended in linear light, like three.js does. */
private fun lin(c: FloatArray): FloatArray = floatArrayOf(
    Math.pow(c[0].toDouble(), 2.2).toFloat(),
    Math.pow(c[1].toDouble(), 2.2).toFloat(),
    Math.pow(c[2].toDouble(), 2.2).toFloat()
)

/** A closed loop of points (LINE_LOOP). */
private fun loopGeo(points: List<FloatArray>): Geo {
    val verts = FloatArray(points.size * 3)
    var k = 0
    for (p in points) { verts[k++] = p[0]; verts[k++] = p[1]; verts[k++] = p[2] }
    return makeGeo(verts, null, points.size)
}

private fun xf(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
    m[0] * v[0] + m[4] * v[1] + m[8] * v[2],
    m[1] * v[0] + m[5] * v[1] + m[9] * v[2],
    m[2] * v[0] + m[6] * v[1] + m[10] * v[2]
)

private fun ss(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun dist(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

private class OrbRenderer(private val onTap: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile var state: JarvisState = JarvisState.IDLE
    @Volatile var level: Float = 0f
    @Volatile var density: Float = 1f

    private var targetScale = 1f
    private var curScale = 1f
    private var viewW = 1
    private var viewH = 1
    private var proj = M.ident()
    private var pxScale = 1f
    private val startNs = System.nanoTime()

    private lateinit var meshP: Prog
    private lateinit var spriteP: Prog
    private lateinit var blurP: Prog
    private lateinit var compP: Prog
    private lateinit var coreG: Geo
    private lateinit var quadG: Geo
    private lateinit var emberP: Prog
    private lateinit var rings: List<Ring>
    private lateinit var strands: List<Strand>
    private lateinit var beams: List<Beam>
    private lateinit var sceneT: Target
    private lateinit var blurA: Target
    private lateinit var blurB: Target
    private lateinit var quarterA: Target
    private lateinit var quarterB: Target

    // Sparks: each one lights up slowly on a ring, lifts off, scatters, then fades out.
    private val EN = 3000
    private val eBuf = FloatArray(EN * 4)     // x, y, z, alpha (interleaved)
    private val eBase = FloatArray(EN * 3)
    private val eTan = FloatArray(EN * 3)
    private val eDir = FloatArray(EN * 3)
    private val eLife = FloatArray(EN)
    private val eAge = FloatArray(EN)
    private val eDelay = FloatArray(EN)
    private val ePhase = FloatArray(EN)
    private var eBufId = 0
    private var lastT = 0f
    private val emberRing = floatArrayOf(1.22f, 1.48f)
    private val emberArc = floatArrayOf(PI.toFloat() * 2f, PI.toFloat() * 1.5f)
    private val emberTilt = arrayOf(M.euler(1.12f, 0.25f, 0.1f), M.euler(0.72f, 1f, 0.4f))
    private var pBufId = 0

    // Touch state (drag turns the orb, a tap toggles the mic).
    private var dragging = false
    private var moved = false
    private var px = 0f
    private var py = 0f
    private var downX = 0f
    private var downY = 0f
    @Volatile private var dragX = 0f
    @Volatile private var dragY = 0f

    fun isNearOrb(x: Float, y: Float): Boolean =
        dist(x - viewW / 2f, y - viewH / 2f) < 0.4f * minOf(viewW, viewH)

    fun onTouch(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true; moved = false
                px = x; py = y; downX = x; downY = y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return
                if (dist(x - downX, y - downY) >= 8f * density) moved = true
                dragY += (x - px) * 0.004f / density
                dragX += (y - py) * 0.004f / density
                px = x; py = y
            }
            MotionEvent.ACTION_UP -> {
                if (dragging && !moved && dist(x - downX, y - downY) < 8f * density) onTap()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        meshP = Prog(VS_MESH, FS_MESH)
        spriteP = Prog(VS_SPRITE, FS_SPRITE)
        blurP = Prog(VS_FULL, FS_BLUR)
        compP = Prog(VS_FULL, FS_COMP)
        emberP = Prog(VS_EMBER, FS_EMBER)

        coreG = sphereGeo(0.74f, 32, 48)   // dark body that hides the far side
        quadG = makeGeo(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), null, 4)

        rings = listOf(
            Ring(torusGeo(1.22f, 0.012f, 16, 300, (PI * 2).toFloat()), floatArrayOf(1.12f, 0.25f, 0.1f), C_ORANGE_GLOW, 0.1f),
            Ring(torusGeo(1.48f, 0.05f, 16, 300, (PI * 1.2).toFloat()), floatArrayOf(0.72f, 1f, 0.4f), C_GOLD, -0.09f)
        )

        // Inner wireframe: slightly wobbly latitude/longitude lines, plus loose great circles at random tilts.
        val sr = 0.76f
        val list = ArrayList<Strand>()
        val parallels = 57
        for (k in 1..parallels) {
            val lat = k.toFloat() / (parallels + 1) * PI.toFloat() + (Random.nextFloat() - 0.5f) * 0.09f
            val r = sr * sin(lat)
            val h = sr * cos(lat)
            val wob = Random.nextFloat() * 6.28f
            val ptsY = ArrayList<FloatArray>()
            val ptsX = ArrayList<FloatArray>()
            for (j in 0 until 240) {
                val ang = j.toFloat() / 240f * PI.toFloat() * 2f
                val rr = r * (1f + 0.02f * sin(ang * 3f + wob))
                ptsY.add(floatArrayOf(rr * cos(ang), h, rr * sin(ang)))
                ptsX.add(floatArrayOf(h, rr * cos(ang), rr * sin(ang)))
            }
            list.add(Strand(tubeGeo(ptsY, true, 0.0065f), C_GOLD, 0.9f + Random.nextFloat() * 0.1f))
            list.add(Strand(tubeGeo(ptsX, true, 0.0065f), C_ORANGE_GLOW, 0.9f + Random.nextFloat() * 0.1f))
        }
        for (k in 0 until 14) {
            val n = norm(floatArrayOf(Random.nextFloat() - 0.5f, Random.nextFloat() - 0.5f, Random.nextFloat() - 0.5f))
            val tmp = if (abs(n[1]) > 0.9f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
            val u = norm(cross(n, tmp))
            val v = cross(n, u)
            val closed = k % 2 == 0
            val cut = if (closed) 1f else 0.55f + Random.nextFloat() * 0.4f
            val seg = 160
            val pts = ArrayList<FloatArray>()
            for (j in 0 until seg) {
                val a = j.toFloat() / seg * 2f * PI.toFloat() * cut
                pts.add(floatArrayOf(
                    sr * (cos(a) * u[0] + sin(a) * v[0]),
                    sr * (cos(a) * u[1] + sin(a) * v[1]),
                    sr * (cos(a) * u[2] + sin(a) * v[2])
                ))
            }
            val col = listOf(C_ORANGE, C_ORANGE_GLOW, C_GOLD)[k % 3]
            list.add(Strand(tubeGeo(pts, closed, 0.0042f), col, 0.6f + Random.nextFloat() * 0.4f))
        }
        strands = list

        // Beams spread evenly over the whole sphere (golden-angle spiral), not bunched in one place.
        val bl = ArrayList<Beam>()
        val nB = 44
        val golden = PI.toFloat() * (3f - sqrt(5f))
        for (i in 0 until nB) {
            val y = 1f - 2f * (i + 0.5f) / nB
            val rr = sqrt(1f - y * y)
            val th = golden * i
            val d = floatArrayOf(cos(th) * rr, y, sin(th) * rr)
            val len = 1.45f + Random.nextFloat() * 0.7f
            val bp = listOf(
                floatArrayOf(d[0] * 0.1f, d[1] * 0.1f, d[2] * 0.1f),
                floatArrayOf(d[0] * len, d[1] * len, d[2] * len)
            )
            bl.add(Beam(tubeGeo(bp, false, if (i % 3 == 0) 0.014f else 0.009f), if (i % 2 == 1) C_ORANGE_GLOW else C_GOLD))
        }
        beams = bl
        beams = bl

        // Sparks start at random points in their cycle, so they do not all move together.
        for (i in 0 until EN) {
            spawnEmber(i)
            ePhase[i] = Random.nextFloat() * PI.toFloat() * 2f
        }
        val eb = IntArray(1)
        GL.glGenBuffers(1, eb, 0)
        eBufId = eb[0]
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, eBufId)
        GL.glBufferData(GL.GL_ARRAY_BUFFER, eBuf.size * 4, null, GL.GL_DYNAMIC_DRAW)

        GL.glDisable(GL.GL_CULL_FACE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width.coerceAtLeast(1)
        viewH = height.coerceAtLeast(1)
        proj = M.persp(38f * PI.toFloat() / 180f, viewW.toFloat() / viewH, 0.1f, 100f)
        pxScale = viewH / 2f
        sceneT = createTarget(viewW, viewH, true)
        blurA = createTarget((viewW shr 1).coerceAtLeast(1), (viewH shr 1).coerceAtLeast(1), false)
        blurB = createTarget((viewW shr 1).coerceAtLeast(1), (viewH shr 1).coerceAtLeast(1), false)
        quarterA = createTarget((viewW shr 2).coerceAtLeast(1), (viewH shr 2).coerceAtLeast(1), false)
        quarterB = createTarget((viewW shr 2).coerceAtLeast(1), (viewH shr 2).coerceAtLeast(1), false)
    }

    private fun createTarget(w: Int, h: Int, withDepth: Boolean): Target {
        val tex = IntArray(1)
        GL.glGenTextures(1, tex, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex[0])
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, w, h, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        val fb = IntArray(1)
        GL.glGenFramebuffers(1, fb, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fb[0])
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, tex[0], 0)
        if (withDepth) {
            val rb = IntArray(1)
            GL.glGenRenderbuffers(1, rb, 0)
            GL.glBindRenderbuffer(GL.GL_RENDERBUFFER, rb[0])
            GL.glRenderbufferStorage(GL.GL_RENDERBUFFER, GL.GL_DEPTH_COMPONENT16, w, h)
            GL.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, rb[0])
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        return Target(fb[0], tex[0], w, h)
    }

    private fun spawnEmber(i: Int) {
        val ring = Random.nextInt(3)   // 0 and 1: the two rings, 2: the sphere's outer shell
        val a = Random.nextFloat() * (if (ring == 2) PI.toFloat() * 2f else emberArc[ring])
        val rad = if (ring == 2) 0.8f + Random.nextFloat() * 0.35f else emberRing[ring]
        val tiltM = if (ring == 2) M.ident() else emberTilt[ring]
        val b = xf(tiltM, floatArrayOf(rad * cos(a), (if (ring == 2) (Random.nextFloat() - 0.5f) * 0.9f else 0f), rad * sin(a)))
        val tg = xf(tiltM, floatArrayOf(-sin(a), 0f, cos(a)))
        val sign = if (Random.nextBoolean()) 1f else -1f
        val out = norm(b)
        val rnd = norm(floatArrayOf(Random.nextFloat() - 0.5f, Random.nextFloat() - 0.5f, Random.nextFloat() - 0.5f))
        val d = norm(floatArrayOf(out[0] * 0.7f + rnd[0] * 0.5f, out[1] * 0.7f + rnd[1] * 0.5f, out[2] * 0.7f + rnd[2] * 0.5f))
        for (k in 0..2) {
            eBase[i * 3 + k] = b[k]
            eTan[i * 3 + k] = tg[k] * sign
            eDir[i * 3 + k] = d[k]
        }
        eLife[i] = 6f + Random.nextFloat() * 4f
        eAge[i] = 0f
        eDelay[i] = Random.nextFloat() * 2f
    }

    private fun updateEmbers(t: Float) {
        val dt = (t - lastT).coerceIn(0f, 0.1f)
        lastT = t
        for (i in 0 until EN) {
            if (eDelay[i] > 0f) {
                eDelay[i] -= dt
                eBuf[i * 4 + 3] = 0f
                continue
            }
            eAge[i] += dt
            if (eAge[i] >= eLife[i]) {
                spawnEmber(i)
                continue
            }
            val u = eAge[i] / eLife[i]
            // Slow light-up on the ring, then lift off and scatter, then a slow fade.
            val fade = ss(0f, 0.15f, u) * (1f - ss(0.75f, 1f, u))
            val twinkle = 0.85f + 0.15f * sin(t * 3f + ePhase[i])
            val sc = maxOf(u - 0.3f, 0f)
            val scatter = sc * sc * 1.8f
            val drift = eAge[i] * 0.12f
            for (k in 0..2) {
                eBuf[i * 4 + k] = eBase[i * 3 + k] + eTan[i * 3 + k] * drift + eDir[i * 3 + k] * scatter
            }
            eBuf[i * 4 + 3] = fade * twinkle
        }
    }

    private fun drawEmbers(mv: FloatArray) {
        GL.glUseProgram(emberP.id)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, eBufId)
        GL.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, eBuf.size * 4, floatBuf(eBuf))
        val aPos = emberP.a("aPos")
        val aA = emberP.a("aA")
        GL.glEnableVertexAttribArray(aPos)
        GL.glVertexAttribPointer(aPos, 3, GL.GL_FLOAT, false, 16, 0)
        GL.glEnableVertexAttribArray(aA)
        GL.glVertexAttribPointer(aA, 1, GL.GL_FLOAT, false, 16, 12)
        GL.glUniformMatrix4fv(emberP.u("uMV"), 1, false, mv, 0)
        GL.glUniformMatrix4fv(emberP.u("uProj"), 1, false, proj, 0)
        GL.glUniform1f(emberP.u("uPxScale"), pxScale)
        val c = lin(C_PARTICLE)
        GL.glUniform4f(emberP.u("uColor"), c[0], c[1], c[2], 1f)
        GL.glUniform1f(emberP.u("uPointSize"), 0.05f)
        GL.glDrawArrays(GL.GL_POINTS, 0, EN)
        GL.glUniform1f(emberP.u("uPointSize"), 0.018f)
        GL.glDrawArrays(GL.GL_POINTS, 0, EN)
    }

    private fun drawMesh(g: Geo, mv: FloatArray, c: FloatArray, alpha: Float, mode: Int, psize: Float, round: Boolean) {
        GL.glUseProgram(meshP.id)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, g.vbo)
        val aPos = meshP.a("aPos")
        GL.glEnableVertexAttribArray(aPos)
        GL.glVertexAttribPointer(aPos, 3, GL.GL_FLOAT, false, 0, 0)
        GL.glUniformMatrix4fv(meshP.u("uMV"), 1, false, mv, 0)
        GL.glUniformMatrix4fv(meshP.u("uProj"), 1, false, proj, 0)
        val lc = lin(c)
        GL.glUniform4f(meshP.u("uColor"), lc[0], lc[1], lc[2], alpha)
        GL.glUniform1f(meshP.u("uPointSize"), psize)
        GL.glUniform1f(meshP.u("uPxScale"), pxScale)
        GL.glUniform1f(meshP.u("uRound"), if (round) 1f else 0f)
        if (g.ibo != 0) {
            GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, g.ibo)
            GL.glDrawElements(mode, g.icount, GL.GL_UNSIGNED_SHORT, 0)
        } else {
            GL.glDrawArrays(mode, 0, g.count)
        }
    }

    /** A soft halo: the same lines drawn again, slightly offset and dimmer, so they look thicker. */
    private fun halo(g: Geo, mv: FloatArray, c: FloatArray, alpha: Float, mode: Int) {
        drawMesh(g, mv, c, alpha, mode, 0f, false)
        drawMesh(g, M.mul(M.trans(0.0013f, 0.0013f, 0f), mv), c, alpha * 0.1f, mode, 0f, false)
        drawMesh(g, M.mul(M.trans(-0.0013f, -0.0013f, 0f), mv), c, alpha * 0.1f, mode, 0f, false)
    }

    private fun drawSprite(size: Float, opacity: Float, hot: Float = 0f) {
        GL.glUseProgram(spriteP.id)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, quadG.vbo)
        val aCorner = spriteP.a("aCorner")
        GL.glEnableVertexAttribArray(aCorner)
        GL.glVertexAttribPointer(aCorner, 2, GL.GL_FLOAT, false, 0, 0)
        GL.glUniform3f(spriteP.u("uCenter"), 0f, 0f, -18f)
        GL.glUniform1f(spriteP.u("uSize"), size * 0.5f)
        GL.glUniformMatrix4fv(spriteP.u("uProj"), 1, false, proj, 0)
        GL.glUniform1f(spriteP.u("uOpacity"), opacity)
        GL.glUniform1f(spriteP.u("uHot"), hot)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun fullPass(p: Prog, tex: Int) {
        GL.glUseProgram(p.id)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, quadG.vbo)
        val aP = p.a("aP")
        GL.glEnableVertexAttribArray(aP)
        GL.glVertexAttribPointer(aP, 2, GL.GL_FLOAT, false, 0, 0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, tex)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!::sceneT.isInitialized) return
        val t = (System.nanoTime() - startNs) / 1e9f
        val s = state
        val lvl = level.coerceIn(0f, 1f)
        targetScale = when (s) {
            JarvisState.SPEAKING -> 1.6f
            JarvisState.LISTENING -> 0.8f + lvl * 0.2f
            else -> 1f
        }
        val speakPulse = if (s == JarvisState.SPEAKING) sin(t * 9f) * 0.08f else 0f
        curScale += (targetScale + speakPulse - curScale) * 0.08f

        val pulse = 1f + sin(t * 2.1f) * 0.05f
        val glowCore = 0.8f * pulse
        val glowInner = 1.65f + sin(t * 1.6f) * 0.04f

        val dx = dragX
        val dy = dragY
        val orbE = M.euler(sin(t * 0.22f) * 0.12f + dx, t * 0.13f + dy, sin(t * 0.15f) * 0.08f)
        val morb = M.mul(orbE, M.scl(curScale))
        val view = M.trans(0f, 0f, -18f)
        val base = M.mul(view, morb)

        updateEmbers(t)

        // Scene pass into an offscreen target.
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, sceneT.fb)
        GL.glViewport(0, 0, sceneT.w, sceneT.h)
        GL.glClearColor(0f, 0f, 0f, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
        GL.glEnable(GL.GL_DEPTH_TEST)

        // Dark body drawn first: it hides the lines on the far side, which gives the orb depth.
        GL.glDisable(GL.GL_BLEND)
        GL.glDepthMask(true)
        drawMesh(coreG, base, C_BODY, 1f, GL.GL_TRIANGLES, 0f, false)

        // Everything else glows: additive blending, no depth writes.
        GL.glEnable(GL.GL_BLEND)
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE)
        GL.glDepthMask(false)

        // Centre glow, breathing and fading out at the edges. No depth test, so it shows over the body.
        GL.glDisable(GL.GL_DEPTH_TEST)
        drawSprite(glowCore * curScale * 2.6f, 0.35f)
        drawSprite(glowCore * curScale * 1.6f, 0.55f)
        drawSprite(glowInner * curScale * 0.8f, 0.8f)
        drawSprite(glowCore * curScale * 0.4f, 1f)
        drawSprite(glowCore * curScale * 0.9f, 1f, 1f)
        drawSprite(glowCore * curScale * 0.35f, 1f, 1f)
        GL.glEnable(GL.GL_DEPTH_TEST)

        for (r in rings) {
            val ringE = M.euler(r.base[0], r.base[1], r.base[2] + r.speed * t * 0.06f)
            halo(r.g, M.mul(base, ringE), r.color, 0.95f, GL.GL_TRIANGLES)
        }

        val strandMv = M.mul(base, M.euler(t * 0.08f, -t * 0.12f, t * 0.035f))
        for (st in strands) halo(st.g, strandMv, st.color, st.alpha, GL.GL_TRIANGLES)

        val beamMv = M.mul(base, M.euler(0f, t * 0.07f, 0f))
        for (b in beams) halo(b.g, beamMv, b.color, 1f, GL.GL_TRIANGLES)

        val partMv = M.mul(base, M.euler(0f, -t * 0.06f, 0f))
        drawEmbers(partMv)

        // Bloom: bright parts blurred at half size, then a wide halo at quarter size.
        GL.glDisable(GL.GL_BLEND)
        GL.glDepthMask(true)
        GL.glDisable(GL.GL_DEPTH_TEST)

        GL.glUseProgram(blurP.id)
        GL.glUniform1i(blurP.u("uTex"), 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, blurA.fb)
        GL.glViewport(0, 0, blurA.w, blurA.h)
        GL.glUniform2f(blurP.u("uStep"), 2f / sceneT.w, 0f)
        GL.glUniform1f(blurP.u("uThresh"), 0.12f)
        GL.glUniform1f(blurP.u("uUseThresh"), 1f)
        fullPass(blurP, sceneT.tex)

        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, blurB.fb)
        GL.glViewport(0, 0, blurB.w, blurB.h)
        GL.glUniform2f(blurP.u("uStep"), 0f, 2f / blurA.h)
        GL.glUniform1f(blurP.u("uUseThresh"), 0f)
        fullPass(blurP, blurA.tex)

        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, quarterA.fb)
        GL.glViewport(0, 0, quarterA.w, quarterA.h)
        GL.glUniform2f(blurP.u("uStep"), 4f / blurB.w, 0f)
        fullPass(blurP, blurB.tex)

        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, quarterB.fb)
        GL.glViewport(0, 0, quarterB.w, quarterB.h)
        GL.glUniform2f(blurP.u("uStep"), 0f, 4f / quarterA.h)
        fullPass(blurP, quarterA.tex)

        // Composite to the screen: scene + bloom + wide halo.
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, viewW, viewH)
        GL.glClearColor(0f, 0f, 0f, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        GL.glUseProgram(compP.id)
        GL.glUniform1i(compP.u("uScene"), 0)
        GL.glUniform1i(compP.u("uBloom"), 1)
        GL.glUniform1i(compP.u("uWide"), 2)
        GL.glUniform1f(compP.u("uStrength"), 0.7f)
        GL.glUniform1f(compP.u("uWideStrength"), 0.4f)
        GL.glActiveTexture(GL.GL_TEXTURE1)
        GL.glBindTexture(GL.GL_TEXTURE_2D, blurB.tex)
        GL.glActiveTexture(GL.GL_TEXTURE2)
        GL.glBindTexture(GL.GL_TEXTURE_2D, quarterB.tex)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, quadG.vbo)
        val aP = compP.a("aP")
        GL.glEnableVertexAttribArray(aP)
        GL.glVertexAttribPointer(aP, 2, GL.GL_FLOAT, false, 0, 0)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, sceneT.tex)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)

        GL.glEnable(GL.GL_DEPTH_TEST)
    }
}
