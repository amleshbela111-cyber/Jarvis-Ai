package com.jarvis.ai.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Connects Jarvis to Shizuku, which runs on this phone after Wireless debugging is paired.
 * Shizuku's permission is granted per app, so only Jarvis gets it. Every call here is safe:
 * if Shizuku is not running, the methods return false or a message instead of crashing.
 */
object ShizukuBridge {

    private const val REQUEST_CODE = 9001

    /** Called when the user answers Shizuku's permission dialog. */
    var onChanged: (() -> Unit)? = null

    private var initialized = false

    private val resultListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_CODE) onChanged?.invoke()
    }

    fun init() {
        if (initialized) return
        initialized = true
        runCatching { Shizuku.addRequestPermissionResultListener(resultListener) }
    }

    /** True when Shizuku is running and Jarvis has its permission. */
    fun isReady(): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shows Shizuku's permission dialog. Returns a short message for the user. */
    fun request(): String = runCatching {
        when {
            Shizuku.isPreV11() -> "Shizuku ka version purana hai. Naya Shizuku install karein."
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "Shizuku pehle se allowed hai."
            else -> {
                Shizuku.requestPermission(REQUEST_CODE)
                "Shizuku mein Jarvis ko allow karein."
            }
        }
    }.getOrElse { "Shizuku chal nahi raha. Pehle Shizuku app khol kar Start karein." }
}
