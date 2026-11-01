package com.jarvis.ai.coding

import android.content.Context
import com.jarvis.ai.device.Outcome
import java.io.File

/**
 * Creates and edits Android projects inside the app's private workspace folder.
 * Paths are checked so nothing can be written outside a project folder.
 */
class ProjectWorkspace(context: Context) {

    private val root: File = File(context.filesDir, "workspace").apply { mkdirs() }

    fun createProject(name: String, template: String): Outcome {
        if (!PROJECT_NAME.matches(name)) {
            return Outcome(false, "Project name is not valid.")
        }
        if (template != "android") {
            return Outcome(false, "Only the 'android' template is available.")
        }
        val dir = File(root, name)
        if (dir.exists()) {
            return Outcome(false, "A project named '$name' already exists.")
        }
        dir.mkdirs()
        for ((path, content) in TEMPLATE_FILES) {
            if (!writeInside(dir, path, content.replace("__PROJECT__", name))) {
                return Outcome(false, "Could not create template file $path.")
            }
        }
        return Outcome(true, "Project '$name' created in the app workspace.")
    }

    fun writeFile(project: String, path: String, content: String): Outcome {
        if (!PROJECT_NAME.matches(project)) return Outcome(false, "Project name is not valid.")
        val dir = File(root, project)
        if (!dir.isDirectory) return Outcome(false, "Project '$project' was not found.")
        if (!writeInside(dir, path, content)) return Outcome(false, "That file path is not allowed.")
        return Outcome(true, "Wrote $path in '$project'.")
    }

    private fun writeInside(base: File, relativePath: String, content: String): Boolean {
        if (!FILE_PATH.matches(relativePath) || relativePath.contains("..")) return false
        val baseCanonical = base.canonicalFile
        val target = File(baseCanonical, relativePath).canonicalFile
        if (!target.path.startsWith(baseCanonical.path + File.separator)) return false
        target.parentFile?.mkdirs()
        target.writeText(content)
        return true
    }

    companion object {
        private val PROJECT_NAME = Regex("[A-Za-z0-9_-]{1,40}")
        private val FILE_PATH = Regex("[A-Za-z0-9_./-]{1,120}")

        /** Minimal Compose project. The Gradle build runs on GitHub Actions, not on the phone. */
        private val TEMPLATE_FILES: Map<String, String> = mapOf(
            "settings.gradle.kts" to """
                pluginManagement {
                    repositories { google(); mavenCentral(); gradlePluginPortal() }
                }
                dependencyResolutionManagement {
                    repositories { google(); mavenCentral() }
                }
                rootProject.name = "__PROJECT__"
                include(":app")
            """.trimIndent() + "\n",
            "build.gradle.kts" to """
                plugins {
                    id("com.android.application") version "8.5.2" apply false
                    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
                    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
                }
            """.trimIndent() + "\n",
            "gradle.properties" to "android.useAndroidX=true\nkotlin.code.style=official\n",
            "app/build.gradle.kts" to """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                    id("org.jetbrains.kotlin.plugin.compose")
                }
                android {
                    namespace = "com.example.app"
                    compileSdk = 35
                    defaultConfig {
                        applicationId = "com.example.app"
                        minSdk = 26
                        targetSdk = 35
                        versionCode = 1
                        versionName = "1.0"
                    }
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                    kotlinOptions { jvmTarget = "17" }
                    buildFeatures { compose = true }
                }
                dependencies {
                    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
                    implementation("androidx.activity:activity-compose:1.9.2")
                    implementation("androidx.compose.material3:material3")
                    implementation("androidx.compose.ui:ui")
                }
            """.trimIndent() + "\n",
            "app/src/main/AndroidManifest.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:label="__PROJECT__" android:supportsRtl="true">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent() + "\n",
            "app/src/main/java/com/example/app/MainActivity.kt" to """
                package com.example.app

                import android.os.Bundle
                import androidx.activity.ComponentActivity
                import androidx.activity.compose.setContent
                import androidx.compose.material3.MaterialTheme
                import androidx.compose.material3.Surface
                import androidx.compose.material3.Text

                class MainActivity : ComponentActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContent {
                            MaterialTheme {
                                Surface { Text("__PROJECT__ is running") }
                            }
                        }
                    }
                }
            """.trimIndent() + "\n",
            "README.md" to "# __PROJECT__\n\nCreated by Jarvis AI. Push to GitHub to build the APK with Actions.\n"
        )
    }
}
