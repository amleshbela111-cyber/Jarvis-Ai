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

    /** Until this time (ms), Jarvis is awake and takes commands without "Hey Jarvis". */
    private var awakeUntil = 0L

    /** Offline "Hey Jarvis" listener (Vosk). Used only while Jarvis is asleep and a model is saved. */
    private var wake: WakeWordListener? = null
    private var wakeListening = false

    override fun onCreate() {
        super.onCreate()
        JarvisCore.init(this)
        voice = VoiceController(this, this)
        wake = WakeWordListener(this) { handler.post { onWakeWord() } }.also { it.preload() }
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
        stopWake()
        voice.stopListening()
        JarvisCore.setVoiceState(JarvisState.IDLE, "Mic band hai. MIC dabakar dobara chalu karein.")
    }

    private fun stopEverything() {
        handler.removeCallbacks(restartRunnable)
        stopWake()
        voice.stopSpeaking()
        JarvisCore.cancelCurrent()
        busy = false
        beginListening()
    }

    private fun beginListening() {
        if (busy || sessionActive || wakeListening || !JarvisCore.snapshot.value.listening) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            JarvisCore.setVoiceState(JarvisState.ERROR, "Microphone permission chahiye.")
            return
        }
        // Asleep and a model is saved: wait for "Hey Jarvis" offline. Google's recognizer stays closed (no beep).
        if (System.currentTimeMillis() >= awakeUntil && VoskModelStore.isReady(this, "en")) {
            wakeListening = true
            JarvisCore.setVoiceState(JarvisState.IDLE, "\"Hey Jarvis\" bolein...")
            if (wake?.start() == true) return
            wakeListening = false
        }
        voice.setLanguage(JarvisCore.settings().speechLanguage)
        sessionActive = true
        voice.startListening()
    }

    /** "Hey Jarvis" was heard: stop the offline listener, stay awake, and start the command recognizer. */
    private fun onWakeWord() {
        if (!wakeListening) return
        stopWake()
        awakeUntil = System.currentTimeMillis() + AWAKE_MS
        JarvisCore.setVoiceState(JarvisState.LISTENING, "Haan, bolein.")
        handler.postDelayed({ beginListening() }, WAKE_HANDOFF_MS)
    }

    private fun stopWake() {
        wakeListening = false
        wake?.stop()
    }

    private fun scheduleRestart(delayMs: Long) {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private fun speakReply(reply: String, speak: Boolean = true) {
        busy = true
        awakeUntil = System.currentTimeMillis() + AWAKE_MS
        val speakOn = JarvisCore.settings().speakResponses
        if (speak && speakOn && voice.speak(reply)) {
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
        val heard = text.trim()

        if (CLOSE_TRIGGER.containsMatchIn(heard)) {
            closeJarvis()
            return
        }

        val awake = System.currentTimeMillis() < awakeUntil
        val command = if (awake) {
            heard
        } else {
            // Asleep: only "Hey Jarvis" starts a command. Anything else is ignored.
            val wake = WAKE_TRIGGER.find(heard)
            if (wake == null) {
                JarvisCore.setVoiceState(JarvisState.IDLE, "\"Hey Jarvis\" bolein...")
                scheduleRestart(RESTART_DELAY_MS)
                return
            }
            heard.substring(wake.range.last + 1).trim()
        }

        busy = true
        if (command.isBlank()) {
            // Only the wake word was said: acknowledge, then wait for the command.
            speakReply("Haan, bolein.")
            return
        }
        JarvisCore.submit(command) { reply, speak -> speakReply(reply, speak) }
    }

    /** "Close Jarvis": stops listening, closes the app screen, and ends this service. */
    private fun closeJarvis() {
        handler.removeCallbacksAndMessages(null)
        busy = false
        sessionActive = false
        awakeUntil = 0L
        stopWake()
        voice.stopListening()
        voice.stopSpeaking()
        JarvisCore.cancelCurrent()
        JarvisCore.setListening(false)
        JarvisCore.setVoiceState(JarvisState.IDLE, "Jarvis band ho gaya.")
        JarvisCore.closeApp()
        stopSelf()
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
        awakeUntil = System.currentTimeMillis() + AWAKE_MS
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
        stopWake()
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

        /** How long Jarvis stays awake after a reply, so follow-up questions need no wake word. */
        private const val AWAKE_MS = 20_000L

        /** Short pause after "Hey Jarvis", so the offline mic is released before the command recognizer opens it. */
        private const val WAKE_HANDOFF_MS = 400L

        private val WAKE_TRIGGER = Regex(
            "(hey|hi|he|हे|हाय)\\s*,?\\s*(jarvis|jarwis|jarvish|javis|जार्विस|जारविस|जार्वीस)",
            RegexOption.IGNORE_CASE
        )
        private val CLOSE_TRIGGER = Regex(
            "(close|band|bund|exit|बंद)\\s*(karo|kar do|करो)?\\s*(jarvis|jarwis|जार्विस|जारविस)" +
                "|(jarvis|jarwis|जार्विस|जारविस)\\s*(close|band|bund|बंद)",
            RegexOption.IGNORE_CASE
        )

        /** Starts the service if needed and sends it [action]. Call only while the app is in the foreground. */
        fun start(context: Context, action: String) {
            val intent = Intent(context, ListeningService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
