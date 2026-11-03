package com.jarvis.ai.ai

import com.jarvis.ai.github.GitHubActions
import com.jarvis.ai.github.GitHubClient
import com.jarvis.ai.github.RepoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * After a push, watches the GitHub build. If it fails, sends the error lines and the project files to Gemini,
 * commits the corrected files, and waits for the next build. Stops after [MAX_ATTEMPTS] tries.
 */
object AppFixer {

    const val MAX_ATTEMPTS = 3
    private const val POLL_MS = 20_000L
    private const val MAX_POLLS = 24
    private const val MAX_PROMPT_CHARS = 60_000

    private val FIX_PROMPT = """
You fix Android build errors. You get the build error lines and the project files.
Reply with JSON only, in this exact shape:
{"files":[{"path":"relative/path","content":"full corrected file text"}]}
Return only the files that must change, with their complete new content. Do not add new files.
""".trimIndent()

    /** Runs in the background. [announce] receives short status lines for the chat. */
    suspend fun watch(
        apiKey: String,
        model: String,
        ownerRepo: String,
        token: String,
        initialFiles: List<RepoFile>,
        pushedAtMs: Long,
        announce: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val actions = GitHubActions(token)
        val client = GitHubClient(token)
        val gemini = GeminiClient()
        var files = initialFiles.associateBy { it.path }
        var attempt = 1
        var sincePush = pushedAtMs

        while (attempt <= MAX_ATTEMPTS) {
            val run = waitForRun(actions, ownerRepo, sincePush)
            if (run == null) {
                announce("Build ka result time par nahi mila. GitHub Actions tab mein dekhein.")
                return@withContext
            }
            if (run.conclusion == "success") {
                announce("Build pass ho gaya. APK GitHub Releases mein ban gaya hai.")
                return@withContext
            }

            val summary = actions.failureSummary(ownerRepo, run.id)
            announce("Build fail hua (koshish $attempt/$MAX_ATTEMPTS). Dikkat: ${summary.lines().firstOrNull() ?: "log nahi mila"}. Sudhar kar dobara bhej raha hoon.")

            val fixed = requestFix(gemini, apiKey, model, summary, files.values.toList())
            if (fixed.isEmpty()) {
                announce("Sudhar nahi ho paya. Error ke saath dobara bolein.")
                return@withContext
            }
            for (file in fixed) {
                client.putFile(ownerRepo, file, "Jarvis: fix build attempt $attempt")
                files = files + (file.path to file)
            }
            sincePush = System.currentTimeMillis()
            attempt++
        }
        announce("$MAX_ATTEMPTS koshishon ke baad bhi build fail hai. Error bata kar dein, main dekhta hoon.")
    }

    /** Waits for a run that was created after the push, and returns it once it has finished. */
    private suspend fun waitForRun(actions: GitHubActions, ownerRepo: String, sincePush: Long): GitHubActions.Run? {
        delay(POLL_MS)
        repeat(MAX_POLLS) {
            val run = runCatching { actions.latestRun(ownerRepo) }.getOrNull()
            if (run != null && run.createdAtMs >= sincePush - 60_000L && run.status == "completed") return run
            delay(POLL_MS)
        }
        return null
    }

    /** Asks Gemini for corrected versions of the files. Only files that already exist in the project are accepted. */
    private suspend fun requestFix(
        gemini: GeminiClient,
        apiKey: String,
        model: String,
        summary: String,
        files: List<RepoFile>
    ): List<RepoFile> {
        val known = files.associateBy { it.path }
        val project = StringBuilder()
        for (f in files) {
            if (project.length + f.content.length > MAX_PROMPT_CHARS) break
            project.append("=== ").append(f.path).append(" ===\n").append(f.content).append("\n\n")
        }
        val prompt = "Build errors:\n$summary\n\nProject files:\n$project"
        val raw = gemini.generate(apiKey, model, FIX_PROMPT, prompt, null, true, 8192)
        val items = JSONObject(stripFence(raw)).optJSONArray("files") ?: return emptyList()
        val result = mutableListOf<RepoFile>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val path = item.optString("path").trim()
            if (!known.containsKey(path) || path.startsWith("/") || path.contains("..")) continue
            result += RepoFile(path, item.optString("content"))
        }
        return result
    }

    private fun stripFence(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }
}
