package com.jarvis.ai.ai

import com.jarvis.ai.github.RepoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.jarvis.ai.settings.JarvisSettings
import org.json.JSONObject

/**
 * Turns a plain-language app idea into a complete Android project, returned as a list of files.
 * Gemini returns JSON only. Nothing is written anywhere until the user confirms the preview.
 */
class AppBuilder(
    private val gemini: GeminiClient,
    /** Returns the OpenRouter key, or null when none is saved. Used only as a backup. */
    private val openRouterKey: () -> String? = { null }
) {

    /** One planned file: where it goes and what it is for. */
    data class PlannedFile(val path: String, val purpose: String)

    /**
     * Two steps. First a short plan (the list of files). Then each file is written in its own call,
     * so a big app does not run out of room in one reply.
     */
    suspend fun build(apiKey: String, model: String, idea: String, groqKey: String? = null): List<RepoFile> {
        val plan = planFiles(apiKey, model, idea, groqKey)
        val files = mutableListOf<RepoFile>()
        var total = 0
        for (entry in plan) {
            val content = writeOneFile(apiKey, model, idea, plan, entry, groqKey, files)
            total += content.length
            require(total <= MAX_TOTAL_CHARS) { "project is too large" }
            files += RepoFile(entry.path, content)
        }
        require(files.isNotEmpty()) { "no files were generated" }
        files += RepoFile(WORKFLOW_PATH, WORKFLOW)
        return files
    }

    /** Asks for the file list only. Every path is checked before any file is written. */
    suspend fun planFiles(apiKey: String, model: String, idea: String, groqKey: String? = null): List<PlannedFile> {
        val raw = ask(
            apiKey = apiKey,
            geminiModel = model,
            groqKey = groqKey,
            route = Route.PLAN,
            system = PLAN_PROMPT,
            user = "App idea: $idea",
            expectJson = true,
            maxTokens = PLAN_MAX_TOKENS
        )
        val items = JSONObject(stripFence(raw)).getJSONArray("files")
        val plan = mutableListOf<PlannedFile>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val path = item.getString("path").trim()
            require(isSafePath(path)) { "unsafe file path: $path" }
            if (path == WORKFLOW_PATH) continue
            plan += PlannedFile(path, item.optString("purpose"))
        }
        require(plan.isNotEmpty()) { "no files were planned" }
        require(plan.size <= PLAN_MAX_FILES) { "too many files (${plan.size})" }
        return plan
    }

    /** Which AI does a call. Kotlin logic uses the Gemini model from Settings. The plan and small files use Groq. */
    enum class Route { PLAN, KOTLIN, SMALL }

    /**
     * One text call on the right AI. If Groq is needed but fails (or has no key), Gemini answers instead,
     * so one failed provider does not stop the whole build.
     */
    private suspend fun ask(
        apiKey: String,
        geminiModel: String,
        groqKey: String?,
        route: Route,
        system: String,
        user: String,
        expectJson: Boolean,
        maxTokens: Int
    ): String {
        // Gemini only. The cheap model for the plan and small files, the model chosen in Settings for Kotlin code.
        // Kotlin code tries the strongest model first. If it is busy or out of quota, the next one answers.
        val models = if (route == Route.KOTLIN) KOTLIN_MODELS else listOf(JarvisSettings.DEFAULT_MODEL)
        var lastError: Exception? = null
        for (model in models) {
            try {
                return gemini.generate(
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = system,
                    userPrompt = user,
                    expectJson = expectJson,
                    maxTokens = maxTokens
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("no model answered")
    }

    /** Writes one file. If the reply is empty, it asks once more before giving up on that file. */
    private suspend fun writeOneFile(
        apiKey: String,
        model: String,
        idea: String,
        plan: List<PlannedFile>,
        entry: PlannedFile,
        groqKey: String?,
        written: List<RepoFile>
    ): String {
        val listing = plan.joinToString("\n") { "- ${it.path}: ${it.purpose}" }
        // Files already written go in full (within a size budget), so later files use the exact same names and APIs.
        val done = StringBuilder()
        for (file in written) {
            if (done.length + file.content.length > CONTEXT_CHARS) break
            done.append("\n--- ${file.path} ---\n").append(file.content).append('\n')
        }
        val context = if (done.isEmpty()) "" else "\n\nFiles already written (keep names and APIs consistent with them):$done"
        val route = if (entry.path.endsWith(".kt") || entry.path.endsWith(".java")) Route.KOTLIN else Route.SMALL
        for (attempt in 1..FILE_ATTEMPTS) {
            val raw = ask(
                apiKey = apiKey,
                geminiModel = model,
                groqKey = groqKey,
                route = route,
                system = FILE_PROMPT,
                user = "App idea: $idea\n\nAll files of this project:\n$listing" + context + "\n\n" +
                    "Write ONLY this file: ${entry.path}\nPurpose: ${entry.purpose}",
                expectJson = false,
                maxTokens = FILE_MAX_TOKENS
            )
            val content = stripFence(raw)
            if (content.isNotBlank()) return content
        }
        throw IllegalStateException("could not write ${entry.path}")
    }

    /** Parses the model's JSON. Rejects unsafe paths and oversized projects. Adds the build workflow. */
    fun parse(raw: String): List<RepoFile> {
        val json = JSONObject(stripFence(raw))
        val items = json.getJSONArray("files")
        val files = mutableListOf<RepoFile>()
        var total = 0
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val path = item.getString("path").trim()
            val content = item.getString("content")
            require(isSafePath(path)) { "unsafe file path: $path" }
            if (path == WORKFLOW_PATH) continue
            total += content.length
            files += RepoFile(path, content)
        }
        require(files.isNotEmpty()) { "no files were generated" }
        require(files.size <= MAX_FILES) { "too many files (${files.size})" }
        require(total <= MAX_TOTAL_CHARS) { "project is too large" }
        files += RepoFile(WORKFLOW_PATH, WORKFLOW)
        return files
    }

    /** Removes a surrounding code fence, with any language word after the opening marks (json, kotlin, xml...). */
    private fun stripFence(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        return t.substringAfter('\n', "").removeSuffix("```").trim()
    }

    private fun isSafePath(path: String): Boolean =
        path.isNotEmpty() && path.length <= 200 && !path.startsWith("/") &&
            !path.contains("..") && !path.contains("\\") && path.none { it.isISOControl() }

    private companion object {
        const val BUILD_MAX_TOKENS = 8192
        const val MAX_FILES = 60
        const val PLAN_MAX_FILES = 40
        const val MAX_TOTAL_CHARS = 500_000
        /** How much of the already-written code is sent with each new file. */
        const val CONTEXT_CHARS = 120_000
        const val WORKFLOW_PATH = ".github/workflows/build-apk.yml"

        // Standard build: same as the Jarvis repo. Each push builds the APK, and main publishes a release.
        // The placeholder @D@ becomes a dollar sign, so the file can be written here without escaping.
        val WORKFLOW = """
name: Build APK

on:
  push:
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.9"

      - name: Build release APK
        run: gradle assembleRelease --no-daemon

      - name: Copy APK with a fixed name
        run: cp app/build/outputs/apk/release/app-release.apk App.apk

      - uses: actions/upload-artifact@v4
        with:
          name: App-apk
          path: App.apk

      - name: Publish release
        if: github.ref == 'refs/heads/main'
        env:
          GH_TOKEN: @D@{{ github.token }}
        run: |
          TAG="build-@D@{{ github.run_number }}"
          gh release create "@D@TAG" App.apk \
            --repo "@D@GITHUB_REPOSITORY" \
            --title "App build @D@{{ github.run_number }}" \
            --notes "Build from commit @D@{GITHUB_SHA::7}." \
            --latest
""".trimStart().replace("@D@", "\$")

        /**
         * Models for the app's Kotlin logic, strongest first. The next one is used when the one before fails
         * (busy, or quota used up for now).
         */
        val KOTLIN_MODELS = listOf("gemini-3.1-pro-preview", "gemini-3.6-flash", "gemini-2.5-flash")
        const val PLAN_MAX_TOKENS = 2048
        const val FILE_MAX_TOKENS = 8192
        const val FILE_ATTEMPTS = 2

        const val PLAN_PROMPT = """
You plan Android projects. Reply with JSON only, no prose, in this exact shape:
{"files":[{"path":"relative/path/from/repo/root","purpose":"one short line"}]}

List every file a complete, buildable Gradle project needs: settings.gradle.kts, build.gradle.kts, gradle.properties, app/build.gradle.kts, app/src/main/AndroidManifest.xml, the Kotlin sources under app/src/main/java, and layouts and values under app/src/main/res.
Use plain Android Views with androidx.appcompat only, unless the idea needs more.
For a heavy app, plan the real structure: data models, repository or storage, business logic, ViewModels or controllers, each screen with its layout, navigation, shared styles, and tests. Up to 40 files is fine when the idea needs it; a simple idea needs far fewer. Do not list .github files.
"""

        const val FILE_PROMPT = """
You write exactly one file of an Android project. Reply with the raw file content only: no markdown fences, no prose.
The file must be complete and valid. No TODO and no placeholders like "..." .
Use compileSdk 35, targetSdk 35, minSdk 26, Kotlin plugin 2.0.21, Android Gradle Plugin 8.5.2.
In app/build.gradle.kts the release build type uses signingConfig = signingConfigs.getByName("debug") and isMinifyEnabled = false.
Use the same names, ids and imports as the other files in the project listing. No secrets in code.
"""

        const val SYSTEM_PROMPT = """
You write complete, buildable Android apps. Reply with JSON only, no prose, in this exact shape:
{"files":[{"path":"relative/path/from/repo/root","content":"full file text"}]}

Rules:
- Create a full Gradle project: settings.gradle.kts (with pluginManagement and repositories google, mavenCentral), build.gradle.kts (root, with plugins apply false), gradle.properties (android.useAndroidX=true), app/build.gradle.kts, app/src/main/AndroidManifest.xml, the Kotlin sources under app/src/main/java, and resources under app/src/main/res.
- Use compileSdk 35, targetSdk 35, minSdk 26, namespace and applicationId matching the app idea, Kotlin plugin 2.0.21, Android Gradle Plugin 8.5.2.
- Use plain Android Views with androidx.appcompat only. Do not use Compose or any other library unless the idea needs it.
- In app/build.gradle.kts, the release build type must use signingConfig = signingConfigs.getByName("debug") and isMinifyEnabled = false, so the release APK installs.
- Each file must be complete and valid. Do not use placeholders like "TODO" or "...".
- Keep it small and working: no network calls unless the idea needs them, and no secrets in code.
- Do not write .github/ files; they are added automatically.
"""
}
}
