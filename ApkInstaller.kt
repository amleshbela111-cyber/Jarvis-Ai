package com.jarvis.ai.apk

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jarvis.ai.device.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a single APK from a direct https link into the app's cache, then hands it
 * to the system installer. The system still asks the user to confirm the install.
 */
class ApkInstaller(private val context: Context) {

    private val dir: File = File(context.cacheDir, "downloads").apply { mkdirs() }

    suspend fun download(url: String): Outcome = withContext(Dispatchers.IO) {
        val name = url.substringAfterLast('/')
        if (!FILE_NAME.matches(name)) {
            return@withContext Outcome(false, "APK file name is not allowed.")
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (connection.responseCode !in 200..299) {
                return@withContext Outcome(false, "Download fail hua: HTTP ${connection.responseCode}")
            }
            val target = File(dir, name)
            var total = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            target.delete()
                            return@withContext Outcome(false, "File 50 MB se badi hai, isliye download rok diya.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            Outcome(true, "$name download ho gayi (${total / 1024} KB). Install karne ke liye bolein.")
        } catch (e: IOException) {
            Outcome(false, "Download fail hua: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    fun install(fileName: String): Outcome {
        if (!FILE_NAME.matches(fileName)) return Outcome(false, "APK file name is not allowed.")
        val file = File(dir, fileName)
        if (!file.isFile) return Outcome(false, "Ye APK pehle download nahi hui hai.")

        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(settings)
                Outcome(false, "Pehle 'Install unknown apps' permission dein. Settings khol di hai, phir dobara bolein.")
            } catch (e: ActivityNotFoundException) {
                Outcome(false, "Is phone par permission screen nahi khul rahi.")
            }
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Outcome(true, "Installer khul gaya hai. Install par tap karke confirm karein.")
        } catch (e: ActivityNotFoundException) {
            Outcome(false, "Is phone par installer nahi mila.")
        }
    }

    private companion object {
        val FILE_NAME = Regex("[A-Za-z0-9._-]{1,120}\\.apk")
        const val MAX_BYTES = 50L * 1024 * 1024
    }
}
