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
import androidx.camera.core.toBitmap
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
}

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
        LiveCamera.active = true
        onDispose {
            LiveCamera.active = false
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

/** Keeps about two frames a second, scaled to 640 px and as JPEG. Frames are not written to disk. */
private fun keepFrame(image: ImageProxy) {
    try {
        val now = System.currentTimeMillis()
        if (!LiveCamera.active || now - lastFrameMs < 500L) return
        lastFrameMs = now
        val bmp = image.toBitmap()
        val m = Matrix()
        val scale = minOf(1f, 640f / maxOf(bmp.width, bmp.height))
        m.postScale(scale, scale)
        m.postRotate(image.imageInfo.rotationDegrees.toFloat())
        val frame = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val out = ByteArrayOutputStream()
        frame.compress(Bitmap.CompressFormat.JPEG, 75, out)
        LiveCamera.latestJpeg = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) {
        // A bad frame is skipped. The next one replaces it.
    } finally {
        image.close()
    }
}
