package com.jarvis.ai.screen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that holds the MediaProjection grant while screen reading is on.
 * Android requires this service to show a notification while capture is active.
 * Frames are taken only when a voice command asks for them.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                startAsForeground()
                if (data == null || resultCode != Activity.RESULT_OK) {
                    stopSelf()
                } else {
                    startProjection(resultCode, data)
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen reading",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis screen reading is on")
            .setContentText("Jarvis can read the screen only when you ask. Tap to stop from Settings.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, data)
        projection = mediaProjection

        // Android 14+ requires a callback to be registered before creating the virtual display.
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                releaseAll()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        // Half resolution keeps memory use low. Gemini does not need full-size frames.
        val width = metrics.widthPixels / 2
        val height = metrics.heightPixels / 2
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "JarvisScreenCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
        instance = this
    }

    /** Returns the most recent frame, or null if none arrives within 3 seconds. */
    suspend fun captureBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        return withTimeoutOrNull(FRAME_TIMEOUT_MS) {
            var bitmap: Bitmap? = null
            while (bitmap == null) {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    bitmap = try {
                        imageToBitmap(image)
                    } finally {
                        image.close()
                    }
                } else {
                    delay(FRAME_POLL_MS)
                }
            }
            bitmap
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / plane.pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
    }

    private fun releaseAll() {
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        virtualDisplay = null
        imageReader = null
        projection = null
        instance = null
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.jarvis.ai.action.START_SCREEN_CAPTURE"
        const val ACTION_STOP = "com.jarvis.ai.action.STOP_SCREEN_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL_ID = "jarvis_screen_reading"
        private const val NOTIFICATION_ID = 41
        private const val FRAME_TIMEOUT_MS = 3_000L
        private const val FRAME_POLL_MS = 100L

        /** Set while capture is active. Null means screen reading is off. */
        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
