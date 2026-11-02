package com.jarvis.ai.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Sends shell commands through Shizuku. Returns null when Shizuku is not ready,
 * so the caller can fall back to the Accessibility service.
 */
object ShizukuShell {

    private const val BIND_TIMEOUT_MS = 5000L

    @Volatile
    private var service: IShellService? = null

    @Volatile
    private var binding: CompletableDeferred<IShellService?>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IShellService.Stub.asInterface(binder)
            binding?.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Runs one command as the shell user. Returns its output, or null if Shizuku cannot run it. */
    suspend fun run(command: String): String? {
        val current = connect() ?: return null
        return runCatching { current.run(command) }.getOrNull()
    }

    /** Takes a screenshot through Shizuku, with no screen-capture prompt. Returns null if not possible. */
    suspend fun screenshot(): ByteArray? {
        val current = connect() ?: return null
        return runCatching { current.screenshot() }.getOrNull()
    }

    private suspend fun connect(): IShellService? {
        service?.let { return it }
        if (!ShizukuBridge.isReady()) return null
        val deferred = CompletableDeferred<IShellService?>()
        binding = deferred
        val bound = runCatching {
            val args = Shizuku.UserServiceArgs(ComponentName("com.jarvis.ai", ShellService::class.java.name))
                .processNameSuffix("jarvis_shell")
                .daemon(false)
            Shizuku.bindUserService(args, connection)
        }.isSuccess
        if (!bound) return null
        return withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
    }
}
