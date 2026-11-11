package com.jarvis.ai.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Shared state for the live camera. Frames stay in memory only. Nothing is saved as a photo. */
object LiveCamera {
    @Volatile var active = false
    @Volatile var latestJpeg: String? = null
    @Volatile var provider: ProcessCameraProvider? = null
    /** Document pages scanned by voice, in order (base64 JPEG). Cleared each time the camera opens. */
    val pages = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var captureRequested = false
    /** Goes up every time the view changes enough to matter (something new came into view). Checked on the phone, so idle views cost nothing. */
    @Volatile var sceneVersion = 0L
    const val MAX_PAGES = 10
}

/** How many of the 64 tiny-picture bits must flip to count as a real change, not just camera noise. */
private const val CHANGE_BITS = 10
private var lastSceneHash = 0L

/** Full-screen live preview. It keeps a small, recent frame for questions. It does not take photos. */
@Composable
fun LiveCameraScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    BackHandler(onBack = onClose)

    DisposableEffect(Unit) {
        LiveCamera.pages.clear()
        LiveCamera.active = true
        // Live watches the camera while this screen is open.
        com.jarvis.ai.core.JarvisCore.cameraScreenChanged(true)
        onDispose {
            LiveCamera.active = false
            com.jarvis.ai.core.JarvisCore.cameraScreenChanged(false)
            LiveCamera.latestJpeg = null
            LiveCamera.provider?.unbindAll()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (granted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                    bindCamera(ctx, previewView, lifecycleOwner)
                    previewView
                }
            )
        } else {
            Text("Camera ki permission dijiye", color = Color.White)
            DisposableEffect(Unit) {
                launcher.launch(Manifest.permission.CAMERA)
                onDispose {}
            }
        }
    }
}

private val frameExecutor = Executors.newSingleThreadExecutor()
private var lastFrameMs = 0L

private fun bindCamera(ctx: android.content.Context, previewView: PreviewView, owner: LifecycleOwner) {
    val future = ProcessCameraProvider.getInstance(ctx)
    future.addListener({
        val provider = future.get()
        LiveCamera.provider = provider
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(frameExecutor) { image -> keepFrame(image) }
        provider.unbindAll()
        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }, ContextCompat.getMainExecutor(ctx))
}

/**
 * Keeps about two frames a second, scaled to 640 px and as JPEG. Frames are not written to disk.
 * When a page scan is requested, the very next frame is kept at higher resolution so text can be read.
 */
private fun keepFrame(image: ImageProxy) {
    try {
        val now = System.currentTimeMillis()
        if (!LiveCamera.active) return
        val capture = LiveCamera.captureRequested
        if (!capture && now - lastFrameMs < 500L) return
        lastFrameMs = now
        // Read the RGBA frame directly, so no extra CameraX helper is needed.
        val plane = image.planes[0]
        val paddedW = plane.rowStride / plane.pixelStride
        val raw = Bitmap.createBitmap(paddedW, image.height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(plane.buffer)
        val bmp = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
        // A 8x8 "fingerprint" of the view (average hash). A change in enough bits means the scene changed.
        val tiny = Bitmap.createScaledBitmap(bmp, 8, 8, true)
        val px = IntArray(64)
        tiny.getPixels(px, 0, 8, 0, 0, 8, 8)
        val gray = DoubleArray(64) { i -> (((px[i] shr 16) and 0xff) + ((px[i] shr 8) and 0xff) + (px[i] and 0xff)) / 3.0 }
        val avg = gray.average()
        var hash = 0L
        for (i in 0 until 64) if (gray[i] > avg) hash = hash or (1L shl i)
        if (java.lang.Long.bitCount(hash xor lastSceneHash) >= CHANGE_BITS) LiveCamera.sceneVersion++
        lastSceneHash = hash
        tiny.recycle()
        val m = Matrix()
        // Small frames: faster upload and a faster answer. Text reading (scans) still uses the big size.
        val maxSide = if (capture) 1280f else 384f
        val scale = minOf(1f, maxSide / maxOf(bmp.width, bmp.height))
        m.postScale(scale, scale)
        m.postRotate(image.imageInfo.rotationDegrees.toFloat())
        val frame = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val out = ByteArrayOutputStream()
        frame.compress(Bitmap.CompressFormat.JPEG, 75, out)
        val jpeg = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        LiveCamera.latestJpeg = jpeg
        if (capture) {
            if (LiveCamera.pages.size < LiveCamera.MAX_PAGES) LiveCamera.pages.add(jpeg)
            LiveCamera.captureRequested = false
        }
    } catch (_: Exception) {
        // A bad frame is skipped. The next one replaces it. A failed scan stops waiting.
        LiveCamera.captureRequested = false
    } finally {
        image.close()
    }
}
