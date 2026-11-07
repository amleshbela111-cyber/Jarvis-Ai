package com.jarvis.ai.walkthrough

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.ai.ai.GeminiClient
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

/** Holds the open 3D walkthrough. Null means closed. */
object Walkthrough {
    val scene = MutableStateFlow<String?>(null)

    /** Runs one fixed command inside the open viewer: next, back, pause, play, toggle, spin. */
    @Volatile var runCommand: ((String) -> Unit)? = null

    fun isOpen(): Boolean = scene.value != null
    fun open(json: String) { scene.value = json }
    fun close() {
        runCommand = null
        scene.value = null
    }
    fun command(c: String) { runCommand?.invoke(c) }
}

/** Asks Gemini for a scene description (JSON only). Returns null when the answer is not usable. */
object WalkthroughBuilder {
    private const val SYSTEM = """You create a detailed 3D walkthrough for a phone viewer, like a teacher explaining a diagram. Reply with JSON only, no markdown.
Schema:
{"title": "short title",
 "parts": [{"name": "unique_id", "label": "short Hinglish label", "type": "a shape name from the list below",
            "pos": [x,y,z], "scale": [sx,sy,sz], "rot": [rx,ry,rz] (radians), "color": "#rrggbb",
            "metal": 0 to 1 (1 = polished metal, 0 = plastic, skin, organ, wood), "rough": 0 to 1 (0 = glossy, 1 = matte), "bump": 0 to 1 (fine surface texture: 0.3 to 0.6 for tissue, skin, bark, leaves; 0 for metal and glass),
            "dir": [dx,dy,dz] (direction the part moves when exploded, length about 1.5)},
           {"name": "unique_id", "label": "...", "from": [x,y,z], "to": [x,y,z], "r": radius, "color": "#rrggbb"}],
 "steps": [{"say": "2-3 short Hinglish sentences: what the part is, where it is, what it does", "hl": ["part names to highlight"],
            "labels": "all" (optional), "explode": 0 to 1,
            "cam": {"yaw": radians, "pitch": radians, "dist": 3 to 12, "target": [x,y,z]}}]}
Shapes:
- sphere: 1 unit across. cylinder: 1 unit tall and wide (scale it). box: 1 unit. torus: about 2.7 units across (a ring).
- cone: 1 unit tall, base 1 unit wide, tip at the top (use for nozzles, funnels, pointed parts). Scale it like a cylinder.
- tube: a cylinder drawn from point "from" to point "to" with radius "r" (use for vessels, pipes, wires, bones).
- arrow: a line with an arrow head, from "from" to "to", radius "r" (use to show blood, air, current or force direction).
- pyramid: square base, apex up, 1 unit tall. prism: triangular, extruded along Y, 1 unit tall. capsule: cylinder with rounded ends, scale = [width, total height, depth].
- ellipsoid: a sphere with unequal scale, e.g. [1,0.6,0.8].
- 2D flat shapes (in the XY plane, visible from both sides): circle2d (disc), ring2d (hollow circle), rect2d (rectangle or square), triangle2d. Size them with scale, e.g. [2,1,1] for a wide rectangle.
- More 3D solids: cube (equal sides), box or cuboid (unequal sides, e.g. [2,1,1]), hemisphere (half sphere), frustum (cone with flat top), tetrahedron, octahedron, icosahedron, dodecahedron, pentprism and hexprism (5 and 6 sided prisms), pentpyramid and hexpyramid, disc (flat cylinder, e.g. scale [1,0.1,1]).
- More 2D shapes: square2d (same as rect2d, use equal scale), pentagon2d, hexagon2d, octagon2d, star2d (5-point star), diamond2d (rhombus), trapezoid2d, parallelogram2d, semicircle2d, ring2d, circle2d (use scale [2,1,1] for an ellipse).
- Custom code (optional top-level field "script", a JavaScript string). Use it for anything that is not a plain shape: moving or spinning things, waves, graphs, charts, particles, flowing blood or current, a drawn diagram, a graph of a function. The script runs once after the scene loads, inside a sandboxed page with no internet. It gets one object "api":
  - api.add("shape name", {name, label, pos:[x,y,z], scale:[x,y,z], rot:[x,y,z], color:"#rrggbb", dir:[x,y,z]}) adds a 3D shape and returns it. Change its pos, rot, scale or color later, inside onFrame, to animate it.
  - api.onFrame(function(api, t, dt) {...}) runs every frame. t is seconds since the script started.
  - api.overlay(function(ctx, t, w, h) {...}) draws 2D on top of the scene with the canvas context ctx (w and h are the screen size in pixels). Use it for 2D diagrams, text, arrows and graphs.
  - api.say("text") speaks a line.
  - api.surface(function(u, v) { return [x, y, z]; }, {name, label, color, metal, rough, res: 40}) builds a smooth, curved 3D surface from a formula. u and v go from 0 to 1. Use it for organic and curved parts of ANY subject: a petal, a leaf, a bottle, a car body, a wing, a bridge arch, a shell, a planet's bulge, a river bend, a lens. Write the real shape with math (sin, cos, exp, and so on), and make it as close to the real object as you can.
  - api.tube(function(t) { return [x, y, z]; }, {name, label, r: 0.05, color, metal, rough}) makes a round tube that follows a curve, for t from 0 to 1. Use it for vessels, pipes, nerves and bent wires.
  Always try for a realistic result, for whatever the subject is. Even if a detail is hard, make the best approximation and use the part's real colour and material.
  Rules: plain JavaScript only, no fetch, no imports, no while(true) loops, no waiting. Keep the script under 120 lines. Use Math for motion, for example pos[1] = 0.3 * Math.sin(t * 2).
- Full list of shape names: sphere, hemisphere, cylinder, cone, frustum, cube, box, cuboid, pyramid, pentpyramid, hexpyramid, tetrahedron, octahedron, icosahedron, dodecahedron, prism, pentprism, hexprism, torus, capsule, ellipsoid, disc, arrow, tube, circle2d, ring2d, rect2d, square2d, triangle2d, pentagon2d, hexagon2d, octagon2d, star2d, diamond2d, trapezoid2d, parallelogram2d, semicircle2d.
Rules:
- Use 25 to 60 parts and 6 to 12 steps. Enough parts to show the real structure: chambers, valves, vessels, main organs, connections.
- The subject is ONLY what the user asked for (their question is in the user message). Build that exact subject, from its real structure. Never substitute a different subject, and never default to the human body, the heart or any organ unless the question asks for it. Examples of the range: an engine, a solar system, a plant cell, a circuit, a bridge, a camera lens, a volcano, a house, a water cycle, a molecule, a computer, a tree.
- Centre the subject near 0,0,0, no wider than about 4 units.
- Give every part a unique name and a short label. Use a warm colour palette: oranges, reds, golds, with a darker red for deeper parts.
- Steps go in teaching order: overall view, then each main part with its function, then how the parts connect and flow, then a short summary step.
- Use "explode" in a later step to pull parts apart and show what sits inside, with a suitable "dir" per part.
- If the photo is only a document or text page, return "parts": [] and make the steps narrate its key points one by one, with "say" only, in clear Hinglish.
- Keep narration accurate. Do not invent facts that are not in the subject or the photo.
- Realism matters. Pick the real colours and materials of THIS subject: metal #b9bcc4 with metal 1 and rough 0.25 (steel, engines, machines), dark iron #3a3d44 with metal 1 and rough 0.4, plastic or rubber in the real colour with metal 0 and rough 0.5, glass or water with a pale tint and rough 0.05, wood #8a5a2b with rough 0.7 and bump 0.4, stone or concrete grey with rough 0.9 and bump 0.3, leaves green with bump 0.3, and for living tissue (only when the subject is biology) flesh red #8a2a22 with rough 0.4 and bump 0.4. Avoid neon colours unless the subject is really neon.
- Make the object look like the real thing: correct proportions, several pieces per part (body, rim, cap, fins, bolts), and a clear layout the user can read.
- Use good proportions, and build each part from several pieces (a body, a rim, a cap) so it looks like the real object.
- For detail that a plain shape cannot show, use the "script" with api.add to add small parts, or api.overlay for labels and arrows."""

    suspend fun build(apiKey: String, model: String, question: String, images: List<String>): String? {
        val client = GeminiClient()
        // Step 1: draw a reference picture of the subject with Gemini's image model (skipped when the camera has pictures).
        val drawn = if (images.isEmpty()) {
            client.generateImage(
                apiKey,
                "Draw a clear, realistic, accurate picture of this, as a textbook diagram seen from the front and slightly above, " +
                    "centred on a plain light background, with real proportions and real colours and materials: $question"
            )
        } else null
        // Step 2: write the 3D code. When a picture exists, the code must copy it as closely as possible.
        val refImages = if (drawn != null) listOf(drawn) + images else images
        val task = if (drawn != null) {
            "User ka sawaal (yahi subject banana hai, koi aur nahi): $question\n" +
                "The first picture is a reference drawing of the subject. Recreate that exact picture in 3D code: " +
                "match its shapes, proportions, layout, colours and materials as closely as you can."
        } else {
            "User ka sawaal (yahi subject banana hai, koi aur nahi): $question"
        }
        val raw = try {
            client.generate(
                apiKey = apiKey,
                model = model,
                systemPrompt = SYSTEM,
                userPrompt = task,
                imageBase64 = refImages.firstOrNull(),
                expectJson = true,
                maxTokens = 4096,
                extraImages = refImages.drop(1)
            )
        } catch (_: Exception) {
            return null
        }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val text = raw.substring(start, end + 1)
        return try {
            JSONObject(text)
            text
        } catch (_: Exception) {
            null
        }
    }
}

/** Speaks the narration with the phone's voice. Works offline. */
class WalkthroughSpeaker(context: Context) {
    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
                ready = true
            }
        }
    }

    fun speak(text: String) {
        if (!ready) return
        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "walkthrough")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

/** Methods the viewer page can call through window.Android. */
class WalkthroughBridge(private val speaker: WalkthroughSpeaker, private val onClose: () -> Unit) {
    @JavascriptInterface
    fun speak(text: String) {
        speaker.speak(text)
    }

    @JavascriptInterface
    fun close() {
        Handler(Looper.getMainLooper()).post { onClose() }
    }
}

/** Full-screen 3D walkthrough. The viewer page is bundled in the app and cannot reach the internet. */
@Composable
fun WalkthroughScreen(json: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val speaker = remember { WalkthroughSpeaker(context) }

    BackHandler { onClose() }

    DisposableEffect(Unit) {
        onDispose {
            Walkthrough.runCommand = null
            speaker.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        // Only the bundled viewer may load. Any network request gets an empty answer.
                        val url = request?.url?.toString() ?: return null
                        return if (url.startsWith("file:///android_asset/")) {
                            null
                        } else {
                            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript("loadScene(${JSONObject.quote(json)})", null)
                    }
                }
                addJavascriptInterface(WalkthroughBridge(speaker, onClose), "Android")
                val wv = this
                Walkthrough.runCommand = { c ->
                    wv.post { wv.evaluateJavascript("cmd('$c')", null) }
                }
                loadUrl("file:///android_asset/walkthrough/viewer.html")
            }
        }
    )
}
