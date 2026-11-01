package com.jarvis.ai.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.ai.MainActivity
import com.jarvis.ai.core.JarvisCore
import com.jarvis.ai.model.JarvisState

/**
 * Keeps Jarvis listening in the background, so commands still work while another app
 * (for example YouTube) is open. It runs as a microphone foreground service with a
 * notification, which Android requires for background microphone use.
 */
class ListeningService : Service(), VoiceController.Listener {

    private lateinit var voice: VoiceController
    private val handler = Handler(Looper.getMainLooper())

    /** True while a command is being processed or Jarvis is speaking. Listening waits. */
    private var busy = false

    /** True while a recognizer session is running. Prevents starting a second one. */
    private var sessionActive = false

    private val restartRunnable = Runnable { beginListening() }

    override fun onCreate() {
        super.onCreate()
        JarvisCore.init(this)
        voice = VoiceController(this, this)
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseListening()
            ACTION_STOP_ALL -> stopEverything()
            else -> resumeListening()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resumeListening() {
        JarvisCore.setListening(true)
        beginListening()
    }

    private fun pauseListening() {
        JarvisCore.setListening(false)
        handler.removeCallbacks(restartRunnable)
        voice.stopListening()
        JarvisCore.setVoiceState(JarvisState.IDLE, "Mic band hai. MIC dabakar dobara chalu karein.")
    }

    private fun stopEverything() {
        handler.removeCallbacks(restartRunnable)
        voice.stopSpeaking()
        JarvisCore.cancelCurrent()
        busy = false
        beginListening()
    }

    private fun beginListening() {
        if (busy || sessionActive || !JarvisCore.snapshot.value.listening) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            JarvisCore.setVoiceState(JarvisState.ERROR, "Microphone permission chahiye.")
            return
        }
        voice.setLanguage(JarvisCore.settings().speechLanguage)
        sessionActive = true
        voice.startListening()
    }

    private fun scheduleRestart(delayMs: Long) {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private fun speakReply(reply: String) {
        busy = true
        val speakOn = JarvisCore.settings().speakResponses
        if (speakOn && voice.speak(reply)) {
            // onSpeechStarted and onSpeechFinished continue the loop from here.
            return
        }
        busy = false
        JarvisCore.setVoiceState(JarvisState.IDLE, "Sun raha hoon...")
        scheduleRestart(RESTART_DELAY_MS)
    }

    // ---------- VoiceController.Listener ----------

    override fun onListeningStarted() {
        sessionActive = true
        JarvisCore.setVoiceState(JarvisState.LISTENING, "Sun raha hoon...")
    }

    override fun onSoundLevel(level: Float) {
        JarvisCore.setSoundLevel(level)
    }

    override fun onPartialResult(text: String) {
        JarvisCore.setPartial(text)
    }

    override fun onFinalResult(text: String) {
        sessionActive = false
        JarvisCore.setPartial("")
        busy = true
        JarvisCore.submit(text) { reply -> speakReply(reply) }
    }

    override fun onListeningStopped() {
        sessionActive = false
        if (!busy) scheduleRestart(RESTART_DELAY_MS)
    }

    override fun onVoiceError(message: String) {
        sessionActive = false
        JarvisCore.setPartial("")
        JarvisCore.setVoiceState(JarvisState.IDLE, message)
        if (!busy) scheduleRestart(RESTART_DELAY_MS * 3)
    }

    override fun onSpeechStarted() {
        busy = true
        JarvisCore.setVoiceState(JarvisState.SPEAKING, "Bol raha hoon...")
    }

    override fun onSpeechFinished() {
        busy = false
        JarvisCore.setVoiceState(JarvisState.IDLE, "Sun raha hoon...")
        scheduleRestart(RESTART_DELAY_MS)
    }

    override fun onTtsReadyChanged(ready: Boolean) {
        JarvisCore.setTtsReady(ready)
    }

    // ---------- Foreground notification ----------

    private fun startAsForeground() {
        val channel = NotificationChannel(CHANNEL_ID, "Jarvis listening", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS is listening")
            .setContentText("Command bolein. Band karne ke liye app mein PAUSE dabaein.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        voice.release()
        JarvisCore.setListening(false)
        JarvisCore.setVoiceState(JarvisState.IDLE, "Jarvis band hai.")
        super.onDestroy()
    }

    companion object {
        const val ACTION_RESUME = "com.jarvis.ai.action.RESUME_LISTENING"
        const val ACTION_PAUSE = "com.jarvis.ai.action.PAUSE_LISTENING"
        const val ACTION_STOP_ALL = "com.jarvis.ai.action.STOP_ALL"

        private const val CHANNEL_ID = "jarvis_listening"
        private const val NOTIFICATION_ID = 42
        private const val RESTART_DELAY_MS = 500L

        /** Starts the service if needed and sends it [action]. Call only while the app is in the foreground. */
        fun start(context: Context, action: String) {
            val intent = Intent(context, ListeningService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
