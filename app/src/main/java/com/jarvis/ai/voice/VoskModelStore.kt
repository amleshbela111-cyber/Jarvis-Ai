package com.jarvis.ai.voice

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Keeps the offline Vosk models inside Jarvis's own storage. The user picks the unzipped model folder
 * from the phone once; Jarvis copies it here. Nothing is downloaded and the APK does not grow.
 */
object VoskModelStore {

    /** "en" for the English (India) model used for "Hey Jarvis", "hi" for Hindi. */
    fun dir(context: Context, lang: String): File = File(context.filesDir, "vosk/$lang")

    /** A model is usable when its acoustic model and config folders are there. */
    fun isReady(context: Context, lang: String): Boolean {
        val d = dir(context, lang)
        return File(d, "am").isDirectory && File(d, "conf").isDirectory
    }

    /** Copies the picked folder into Jarvis storage. Returns false when the folder is not a Vosk model. */
    fun importFolder(context: Context, treeUri: Uri, lang: String): Boolean {
        val source = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        val target = dir(context, lang)
        target.deleteRecursively()
        copyTree(context, source, target)
        return isReady(context, lang)
    }

    private fun copyTree(context: Context, source: DocumentFile, target: File) {
        target.mkdirs()
        for (child in source.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                copyTree(context, child, File(target, name))
            } else if (child.isFile) {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    File(target, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
