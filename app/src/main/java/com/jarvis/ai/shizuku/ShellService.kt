package com.jarvis.ai.shizuku

/**
 * Runs inside Shizuku's own process, which has shell-level rights. It only runs the commands
 * that Jarvis sends it (tap, swipe, text, key, and screen dump), and nothing else.
 */
class ShellService : IShellService.Stub() {

    override fun run(command: String): String = try {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output
    } catch (e: Exception) {
        ""
    }

    /** Takes a screenshot with the system's own tool. Half size JPEG keeps it small enough to send back. */
    override fun screenshot(): ByteArray {
        return try {
            val process = ProcessBuilder("sh", "-c", "screencap -p").start()
            val png = process.inputStream.use { it.readBytes() }
            process.waitFor()
            val full = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size) ?: return ByteArray(0)
            val small = android.graphics.Bitmap.createScaledBitmap(full, full.width / 2, full.height / 2, true)
            val out = java.io.ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            out.toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
