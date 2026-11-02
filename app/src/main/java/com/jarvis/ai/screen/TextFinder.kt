package com.jarvis.ai.screen

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jarvis.ai.shizuku.ShizukuShell
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Finds small on-screen text with on-device OCR. Used when the accessibility tree does not list an item.
 * The screenshot may be smaller than the real screen, so the centre is scaled to full screen pixels.
 */
object TextFinder {

    /** Centre of the first text line whose words contain every word of [label], in full-screen pixels. */
    suspend fun findCenter(label: String, screenWidth: Int): Pair<Int, Int>? {
        val wanted = label.lowercase()
            .split(Regex("\\s+"))
            .map { word -> word.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return null

        val bytes = ShizukuShell.screenshot()
        if (bytes == null || bytes.isEmpty()) return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bitmap.width <= 0) return null
        val scale = screenWidth.toFloat() / bitmap.width

        val result = recognize(bitmap) ?: return null
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val text = line.text.lowercase().filter { it.isLetterOrDigit() || it == ' ' }
                if (wanted.all { text.contains(it) }) {
                    return (box.exactCenterX() * scale).toInt() to (box.exactCenterY() * scale).toInt()
                }
            }
        }
        return null
    }

    private suspend fun recognize(bitmap: android.graphics.Bitmap): Text? =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
                .addOnCompleteListener { recognizer.close() }
        }
}
