package com.jarvis.ai.github

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Reads build results from GitHub Actions: the latest run, and the error lines of a failed job. */
class GitHubActions(private val token: String) {

    data class Run(val id: Long, val status: String, val conclusion: String?, val createdAtMs: Long)

    /** The most recent workflow run in "owner/repo", or null when there is none. */
    fun latestRun(ownerRepo: String): Run? {
        val runs = JSONObject(get("https://api.github.com/repos/$ownerRepo/actions/runs?per_page=1"))
            .optJSONArray("workflow_runs") ?: return null
        if (runs.length() == 0) return null
        val run = runs.getJSONObject(0)
        val conclusion = run.optString("conclusion").takeIf { it.isNotBlank() && it != "null" }
        val createdAt = run.optString("created_at")
        val createdMs = runCatching { java.time.Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L)
        return Run(run.getLong("id"), run.optString("status"), conclusion, createdMs)
    }

    /** Error lines from the first failed job of [runId], shortened so they fit in a prompt. */
    fun failureSummary(ownerRepo: String, runId: Long): String {
        val jobs = JSONObject(get("https://api.github.com/repos/$ownerRepo/actions/runs/$runId/jobs"))
            .optJSONArray("jobs") ?: return ""
        for (i in 0 until jobs.length()) {
            val job = jobs.getJSONObject(i)
            if (job.optString("conclusion") != "failure") continue
            val log = get("https://api.github.com/repos/$ownerRepo/actions/jobs/${job.getLong("id")}/logs")
            return log.lines()
                .filter { ERROR_LINE.containsMatchIn(it) }
                .take(40)
                .joinToString("\n")
                .take(4000)
        }
        return ""
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("GitHub ${code}: ${body.take(120)}")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val ERROR_LINE = Regex("e: file://|error:|Execution failed|What went wrong|FAILURE", RegexOption.IGNORE_CASE)
    }
}
