package com.jarvis.ai.github

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One file to commit. [path] is relative to the repo root, for example "app/src/main/AndroidManifest.xml". */
data class RepoFile(val path: String, val content: String)

/**
 * Minimal GitHub contents API client. The token goes in a header only, never in the URL.
 * Each call creates or updates one file on the repo's default branch.
 */
class GitHubClient(private val token: String) {

    /** Creates or updates [file] in "owner/repo". Throws an IOException with a short message on failure. */
    fun putFile(ownerRepo: String, file: RepoFile, message: String) {
        val sha = existingSha(ownerRepo, file.path)
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(file.content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (sha != null) body.put("sha", sha)

        val (code, text) = retried { request("PUT", ownerRepo, file.path, body.toString()) }
        if (code !in 200..299) throw IOException("GitHub ${code} on ${file.path}: ${text.take(160).ifBlank { "server error, try again" }}")
    }

    /** Deletes [path] from "owner/repo". [sha] is the file's blob sha, as [listFiles] returns it. */
    fun deleteFile(ownerRepo: String, path: String, sha: String, message: String) {
        val body = JSONObject().put("message", message).put("sha", sha)
        val (code, text) = retried { request("DELETE", ownerRepo, path, body.toString()) }
        if (code !in 200..299) throw IOException("GitHub ${code} on ${path}: ${text.take(160).ifBlank { "server error, try again" }}")
    }

    /**
     * Every file in the repo's default branch, as (path, blob sha) pairs. An empty repo gives an empty list.
     */
    fun listFiles(ownerRepo: String): List<Pair<String, String>> {
        val url = URL("https://api.github.com/repos/$ownerRepo/git/trees/HEAD?recursive=1")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = connection.responseCode
            // 404 and 409 mean the repo has no commits yet, so there is nothing to list.
            if (code == 404 || code == 409) return emptyList()
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("GitHub ${code}: ${text.take(160)}")
            val tree = JSONObject(text).optJSONArray("tree") ?: return emptyList()
            val files = mutableListOf<Pair<String, String>>()
            for (i in 0 until tree.length()) {
                val item = tree.getJSONObject(i)
                if (item.optString("type") == "blob") files += item.getString("path") to item.getString("sha")
            }
            return files
        } finally {
            connection.disconnect()
        }
    }

    /** Returns the blob sha of an existing file, or null when the file does not exist yet. */
    private fun existingSha(ownerRepo: String, path: String): String? {
        val (code, text) = request("GET", ownerRepo, path, null)
        if (code == 404) return null
        if (code !in 200..299) throw IOException("GitHub ${code}: ${text.take(160)}")
        return JSONObject(text).optString("sha").takeIf { it.isNotBlank() }
    }

    /** GitHub sometimes answers 500 for a moment. Retry those up to 3 times with a short wait. Other answers are returned as they are. */
    private fun retried(call: () -> Pair<Int, String>): Pair<Int, String> {
        var result = call()
        var attempt = 1
        while (result.first in 500..599 && attempt < 3) {
            Thread.sleep(1500L * attempt)
            result = call()
            attempt++
        }
        return result
    }

    private fun request(method: String, ownerRepo: String, path: String, body: String?): Pair<Int, String> {
        val encodedPath = path.split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        val url = URL("https://api.github.com/repos/$ownerRepo/contents/$encodedPath")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            connection.disconnect()
        }
    }
}
