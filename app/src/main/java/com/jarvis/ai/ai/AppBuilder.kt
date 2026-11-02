package com.jarvis.ai.ai

import com.jarvis.ai.github.RepoFile
import org.json.JSONObject

/**
 * Turns a plain-language app idea into a complete Android project, returned as a list of files.
 * Gemini returns JSON only. Nothing is written anywhere until the user confirms the preview.
 */
class AppBuilder(private val gemini: GeminiClient) {

    suspend fun build(apiKey: String, model: String, idea: String): List<RepoFile> {
        val raw = gemini.generate(
            apiKey = apiKey,
            model = model,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = "App idea (write the app in this idea): $idea",
            expectJson = true,
            maxTokens = BUILD_MAX_TOKENS
        )
        return parse(raw)
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

    private fun stripFence(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private fun isSafePath(path: String): Boolean =
        path.isNotEmpty() && path.length <= 200 && !path.startsWith("/") &&
            !path.contains("..") && !path.contains("\\") && path.none { it.isISOControl() }

    private companion object {
        const val BUILD_MAX_TOKENS = 8192
        const val MAX_FILES = 60
        const val MAX_TOTAL_CHARS = 200_000
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
