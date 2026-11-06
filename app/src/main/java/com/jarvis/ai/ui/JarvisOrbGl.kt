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
        modifier = modifier.size(340.dp),
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

private val C_ORANGE = hex(0xA9430B)
private val C_ORANGE_DARK = hex(0x652407)
private val C_ORANGE_GLOW = hex(0xC65A12)
private val C_GOLD = hex(0x986019)
private val C_CORE = hex(0xA84C10)
private val C_PARTICLE = hex(0xA95A16)

// ---------- shaders (same as the WebGL version) ----------
private const val VS_MESH = """
attribute vec3 aPos;
uniform mat4 uMV;
uniform mat4 uProj;
uniform float uPointSize;
uniform float uPxScale;
void main(){
  vec4 p = uMV * vec4(aPos, 1.0);
  gl_Position = uProj * p;
  gl_PointSize = uPointSize * uPxScale / max(-p.z, 0.1);
}
"""

private const val FS_MESH = """
precision mediump float;
uniform vec4 uColor;
uniform float uRound;
void main(){
  float a = uColor.a;
  if (uRound > 0.5) {
    float r = length(gl_PointCoord - 0.5) * 2.0;
    if (r > 1.0) discard;
    a *= 1.0 - smoothstep(0.3, 1.0, r);
  }
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
  gl_FragColor = vec4(c.rgb, c.a * uOpacity);
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
  gl_FragColor = vec4(min(c, 1.0), 1.0);
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

/** A closed loop of points (LINE_LOOP). */
private fun loopGeo(points: List<FloatArray>): Geo {
    val verts = FloatArray(points.size * 3)
    var k = 0
    for (p in points) { verts[k++] = p[0]; verts[k++] = p[1]; verts[k++] = p[2] }
    return makeGeo(verts, null, points.size)
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
    private lateinit var particleBuf: Geo
    private lateinit var rings: List<Ring>
    private lateinit var strands: List<Strand>
    private lateinit var beams: List<Beam>
    private lateinit var sceneT: Target
    private lateinit var blurA: Target
    private lateinit var blurB: Target
    private lateinit var quarterA: Target
    private lateinit var quarterB: Target

    private val COUNT = 5200
    private val pPos = FloatArray(COUNT * 3)
    private val pSpeed = FloatArray(COUNT)
    private val pPhase = FloatArray(COUNT)
    private val pDrift = FloatArray(COUNT)
    private val pTheta = FloatArray(COUNT)
    private val pPhi = FloatArray(COUNT)
    private val pRadius = FloatArray(COUNT)
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

        coreG = sphereGeo(0.105f, 24, 32)
        quadG = makeGeo(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), null, 4)

        rings = listOf(
            Ring(torusGeo(1.12f, 0.018f, 16, 300, (PI * 2).toFloat()), floatArrayOf(1.12f, 0.25f, 0.1f), C_ORANGE_GLOW, 0.1f),
            Ring(torusGeo(1.28f, 0.023f, 16, 300, (PI * 2).toFloat()), floatArrayOf(0.72f, 1f, 0.4f), C_GOLD, 0.1f),
            Ring(torusGeo(1.52f, 0.027f, 16, 300, (PI * 1.52).toFloat()), floatArrayOf(1.35f, 0.4f, 0.7f), C_ORANGE, 0.12f),
            Ring(torusGeo(1.62f, 0.019f, 16, 300, (PI * 1.65).toFloat()), floatArrayOf(0.85f, 1.35f, 0.15f), C_GOLD, -0.09f),
            Ring(torusGeo(1.38f, 0.014f, 16, 300, (PI * 1.3).toFloat()), floatArrayOf(1.6f, 0.8f, 1f), C_ORANGE_DARK, 0.16f)
        )

        // Inner wireframe: 24 great circles spread evenly, plus 7 latitude rings.
        val sr = 0.76f
        val list = ArrayList<Strand>()
        val circleCount = 24
        for (i in 0 until circleCount) {
            val y = 1f - (i.toFloat() / (circleCount - 1)) * 2f
            val rad = sqrt(1f - y * y)
            val th = i * PI.toFloat() * (3f - sqrt(5f))
            val n = norm(floatArrayOf(cos(th) * rad, y, sin(th) * rad))
            val ref = if (abs(n[1]) > 0.95f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
            val u = norm(cross(n, ref))
            val v = norm(cross(n, u))
            val pts = ArrayList<FloatArray>()
            for (j in 0 until 240) {
                val a = j.toFloat() / 240f * PI.toFloat() * 2f
                pts.add(floatArrayOf(
                    sr * (cos(a) * u[0] + sin(a) * v[0]),
                    sr * (cos(a) * u[1] + sin(a) * v[1]),
                    sr * (cos(a) * u[2] + sin(a) * v[2])
                ))
            }
            list.add(Strand(loopGeo(pts), if (i % 4 == 0) C_ORANGE_GLOW else C_GOLD, 0.85f))
        }
        for (i in 1..7) {
            val lat = i.toFloat() / 8f * PI.toFloat()
            val r = sr * sin(lat)
            val yy = sr * cos(lat)
            val pts = ArrayList<FloatArray>()
            for (j in 0 until 240) {
                val aa = j.toFloat() / 240f * PI.toFloat() * 2f
                pts.add(floatArrayOf(r * cos(aa), yy, r * sin(aa)))
            }
            list.add(Strand(loopGeo(pts), C_ORANGE_GLOW, 0.72f))
        }
        strands = list

        // Radial beams (random tilt, like the original).
        val bl = ArrayList<Beam>()
        for (i in 0 until 11) {
            val angle = i * PI.toFloat() * 2f / 11f
            val len = 1.5f + Random.nextFloat() * 0.35f
            val d = norm(floatArrayOf(cos(angle), sin(angle), (Random.nextFloat() - 0.5f) * 0.5f))
            val verts = floatArrayOf(d[0] * 0.1f, d[1] * 0.1f, d[2] * 0.1f, d[0] * len, d[1] * len, d[2] * len)
            bl.add(Beam(makeGeo(verts, null, 2), if (i % 2 == 1) C_ORANGE else C_GOLD))
        }
        beams = bl

        // Particles: positions are updated on the CPU and uploaded every frame.
        for (i in 0 until COUNT) {
            pTheta[i] = Random.nextFloat() * PI.toFloat() * 2f
            pPhi[i] = kotlin.math.acos(2f * Random.nextFloat() - 1f)
            pRadius[i] = 1.15f + Random.nextFloat() * 0.78f
            pSpeed[i] = 0.12f + Random.nextFloat() * 0.65f
            pPhase[i] = Random.nextFloat() * PI.toFloat() * 2f
            pDrift[i] = (Random.nextFloat() - 0.5f) * 0.16f
        }
        val pb = IntArray(1)
        GL.glGenBuffers(1, pb, 0)
        pBufId = pb[0]
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, pBufId)
        GL.glBufferData(GL.GL_ARRAY_BUFFER, pPos.size * 4, null, GL.GL_DYNAMIC_DRAW)
        particleBuf = Geo(pBufId, COUNT)

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

    private fun updateParticles(t: Float) {
        for (i in 0 until COUNT) {
            val theta = pTheta[i] + t * pSpeed[i] * 0.32f
            val phi = pPhi[i] + sin(t * 0.4f + pPhase[i]) * pDrift[i]
            val r = pRadius[i] + sin(t * 1.8f + pPhase[i]) * 0.025f
            pPos[i * 3] = r * sin(phi) * cos(theta)
            pPos[i * 3 + 1] = r * sin(phi) * sin(theta)
            pPos[i * 3 + 2] = r * cos(phi)
        }
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, pBufId)
        GL.glBufferSubData(GL.GL_ARRAY_BUFFER, 0, pPos.size * 4, floatBuf(pPos))
    }

    private fun drawMesh(g: Geo, mv: FloatArray, c: FloatArray, alpha: Float, mode: Int, psize: Float, round: Boolean) {
        GL.glUseProgram(meshP.id)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, g.vbo)
        val aPos = meshP.a("aPos")
        GL.glEnableVertexAttribArray(aPos)
        GL.glVertexAttribPointer(aPos, 3, GL.GL_FLOAT, false, 0, 0)
        GL.glUniformMatrix4fv(meshP.u("uMV"), 1, false, mv, 0)
        GL.glUniformMatrix4fv(meshP.u("uProj"), 1, false, proj, 0)
        GL.glUniform4f(meshP.u("uColor"), c[0], c[1], c[2], alpha)
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
        drawMesh(g, M.mul(M.trans(0.0013f, 0.0013f, 0f), mv), c, alpha * 0.2f, mode, 0f, false)
        drawMesh(g, M.mul(M.trans(-0.0013f, -0.0013f, 0f), mv), c, alpha * 0.2f, mode, 0f, false)
    }

    private fun drawSprite(size: Float, opacity: Float) {
        GL.glUseProgram(spriteP.id)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, quadG.vbo)
        val aCorner = spriteP.a("aCorner")
        GL.glEnableVertexAttribArray(aCorner)
        GL.glVertexAttribPointer(aCorner, 2, GL.GL_FLOAT, false, 0, 0)
        GL.glUniform3f(spriteP.u("uCenter"), 0f, 0f, -6.3f)
        GL.glUniform1f(spriteP.u("uSize"), size * 0.5f)
        GL.glUniformMatrix4fv(spriteP.u("uProj"), 1, false, proj, 0)
        GL.glUniform1f(spriteP.u("uOpacity"), opacity)
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
            JarvisState.SPEAKING -> 1.35f
            JarvisState.LISTENING -> 0.9f + lvl * 0.25f
            else -> 1f
        }
        val speakPulse = if (s == JarvisState.SPEAKING) sin(t * 9f) * 0.04f else 0f
        curScale += (targetScale + speakPulse - curScale) * 0.08f

        val pulse = 1f + sin(t * 2.1f) * 0.05f
        val glowCore = 0.8f * pulse
        val glowInner = 1.65f + sin(t * 1.6f) * 0.04f

        val dx = dragX
        val dy = dragY
        val orbE = M.euler(sin(t * 0.22f) * 0.12f + dx, t * 0.13f + dy, sin(t * 0.15f) * 0.08f)
        val morb = M.mul(orbE, M.scl(curScale))
        val view = M.trans(0f, 0f, -6.3f)
        val base = M.mul(view, morb)

        updateParticles(t)

        // Scene pass into an offscreen target.
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, sceneT.fb)
        GL.glViewport(0, 0, sceneT.w, sceneT.h)
        GL.glClearColor(3f / 255f, 1f / 255f, 0f, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
        GL.glEnable(GL.GL_DEPTH_TEST)

        // Core: solid, drawn first.
        GL.glDisable(GL.GL_BLEND)
        GL.glDepthMask(true)
        drawMesh(coreG, M.mul(base, M.scl(pulse)), C_CORE, 1f, GL.GL_TRIANGLES, 0f, false)

        // Everything else glows: additive blending, no depth writes.
        GL.glEnable(GL.GL_BLEND)
        GL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE)
        GL.glDepthMask(false)

        drawSprite(glowCore * curScale, 0.9f)
        drawSprite(glowInner * curScale, 0.48f)
        drawSprite(glowCore * curScale * 1.6f, 0.3f)

        for (r in rings) {
            val ringE = M.euler(r.base[0], r.base[1], r.base[2] + r.speed * t * 0.06f)
            halo(r.g, M.mul(base, ringE), r.color, 0.95f, GL.GL_TRIANGLES)
        }

        val strandMv = M.mul(base, M.euler(t * 0.08f, -t * 0.12f, t * 0.035f))
        for (st in strands) halo(st.g, strandMv, st.color, st.alpha, GL.GL_LINE_LOOP)

        val beamMv = M.mul(base, M.euler(0f, t * 0.07f, 0f))
        for (b in beams) halo(b.g, beamMv, b.color, 0.9f, GL.GL_LINE_STRIP)

        val partMv = M.mul(base, M.euler(0f, -t * 0.06f, 0f))
        drawMesh(particleBuf, partMv, C_PARTICLE, 0.1f, GL.GL_POINTS, 0.036f, true)
        drawMesh(particleBuf, partMv, C_PARTICLE, 0.88f, GL.GL_POINTS, 0.014f, true)

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
        GL.glUniform1f(compP.u("uStrength"), 0.55f)
        GL.glUniform1f(compP.u("uWideStrength"), 0.35f)
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
