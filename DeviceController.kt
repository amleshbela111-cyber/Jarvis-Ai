package com.jarvis.ai.device

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Result of a device action. [success] is true only after the change was read back or confirmed. */
data class Outcome(val success: Boolean, val message: String)

/**
 * Device controls using official Android APIs. Every change is read back and
 * confirmed before Jarvis reports success. Restricted settings open the matching
 * Android screen instead of being bypassed.
 */
class DeviceController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------- Flashlight ----------

    /** Turns the torch on or off. Success is reported only when the torch callback confirms it. */
    suspend fun setFlashlight(on: Boolean): Outcome {
        val cameraManager = context.getSystemService(CameraManager::class.java)
            ?: return Outcome(false, "Camera service is not available on this phone.")

        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: CameraAccessException) {
            return Outcome(false, "Camera access failed: ${e.message}")
        } ?: return Outcome(false, "This phone has no flashlight.")

        var callback: CameraManager.TorchCallback? = null
        return try {
            val confirmed = withTimeoutOrNull(TORCH_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val torchCallback = object : CameraManager.TorchCallback() {
                        override fun onTorchModeChanged(id: String, enabled: Boolean) {
                            if (id == cameraId && enabled == on && continuation.isActive) {
                                continuation.resume(true)
                            }
                        }
                    }
                    callback = torchCallback
                    cameraManager.registerTorchCallback(torchCallback, mainHandler)
                    cameraManager.setTorchMode(cameraId, on)
                }
            }
            if (confirmed == true) {
                Outcome(true, "Flashlight is ${if (on) "on" else "off"}.")
            } else {
                Outcome(false, "Flashlight state could not be confirmed.")
            }
        } catch (e: CameraAccessException) {
            Outcome(false, "Flashlight failed: ${e.message}")
        } finally {
            callback?.let { cameraManager.unregisterTorchCallback(it) }
        }
    }

    // ---------- Media volume ----------

    /** Sets the media (music/video) volume to [percent] of its maximum, then reads it back. */
    fun setMediaVolume(percent: Int): Outcome {
        val audioManager = context.getSystemService(AudioManager::class.java)
            ?: return Outcome(false, "Audio service is not available.")
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return Outcome(false, "Media volume cannot be changed on this phone.")

        val target = (percent.coerceIn(0, 100) * max + 50) / 100
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        val actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val actualPercent = actual * 100 / max

        return if (actual == target) {
            Outcome(true, "Media volume is now about $actualPercent percent.")
        } else {
            Outcome(false, "Media volume did not change as requested. It is at $actualPercent percent.")
        }
    }

    // ---------- Brightness ----------

    /** True when the user has granted "Modify system settings" to Jarvis. */
    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    /** Sets screen brightness to [percent] (0..100 mapped to 0..255), then reads it back. */
    fun setBrightness(percent: Int): Outcome {
        if (!canWriteSettings()) {
            openWriteSettingsScreen()
            return Outcome(
                false,
                "Brightness ke liye 'Modify system settings' permission chahiye. Permission screen khol di hai. Allow karke dobara bolein."
            )
        }
        val resolver = context.contentResolver
        val value = (percent.coerceIn(0, 100) * 255 + 50) / 100

        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        val wrote = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
        val readBack = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)

        return if (wrote && readBack == value) {
            Outcome(true, "Brightness is now $percent percent.")
        } else {
            Outcome(false, "Brightness did not change.")
        }
    }

    fun openWriteSettingsScreen() {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    // ---------- Apps ----------

    data class LaunchableApp(val label: String, val packageName: String)

    /** Apps that have a launcher icon. Visibility on Android 11+ depends on the <queries> entry in the manifest. */
    fun launchableApps(): List<LaunchableApp> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                LaunchableApp(info.loadLabel(pm).toString(), pkg)
            }
            .distinctBy { it.packageName }
    }

    /** Opens an app by its installed name, or by package name if the plan gives one. */
    fun launchApp(appName: String?, packageName: String?): Outcome {
        val pm = context.packageManager

        val targetPackage: String = if (!packageName.isNullOrBlank()) {
            packageName
        } else {
            val name = appName.orEmpty().trim()
            val apps = launchableApps()
            val exact = apps.filter { it.label.equals(name, ignoreCase = true) }
            val matches = if (exact.isNotEmpty()) exact else apps.filter { it.label.contains(name, ignoreCase = true) }
            when {
                matches.isEmpty() -> return Outcome(false, "No app named \"$name\" is installed.")
                matches.size > 1 -> return Outcome(
                    false,
                    "More than one app matches \"$name\": ${matches.joinToString { it.label }}. Please say the full name."
                )
                else -> matches.first().packageName
            }
        }

        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
            ?: return Outcome(false, "That app cannot be opened.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            Outcome(true, "Opening the app.")
        } catch (e: ActivityNotFoundException) {
            Outcome(false, "That app cannot be opened.")
        }
    }

    // ---------- Screen lock (device admin) ----------

    private fun adminComponent() = ComponentName(context, JarvisDeviceAdminReceiver::class.java)

    fun isAdminActive(): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(adminComponent()) == true

    /** Locks the screen only if the user has enabled device admin. Otherwise it opens the admin screen. */
    fun lockScreen(): Outcome {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
            ?: return Outcome(false, "Device policy service is not available.")
        if (!dpm.isAdminActive(adminComponent())) {
            requestDeviceAdmin()
            return Outcome(false, "Screen lock ke liye device admin allow karna hoga. Screen khol di hai.")
        }
        return try {
            dpm.lockNow()
            Outcome(true, "Lock command sent.")
        } catch (e: SecurityException) {
            Outcome(false, "Lock was refused: ${e.message}")
        }
    }

    fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Lets Jarvis lock the screen when you ask. It cannot do anything else with this permission."
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val TORCH_TIMEOUT_MS = 3_000L
    }
}
