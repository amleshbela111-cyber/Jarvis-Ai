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

    override fun destroy() {
        System.exit(0)
    }
}
