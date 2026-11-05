plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jarvis.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.ai"
        minSdk = 26
        targetSdk = 35
        ndk {
            // Modern phones are 64-bit ARM. One ABI keeps the APK small.
            abiFilters += listOf("arm64-v8a")
        }
        versionCode = 42
        versionName = "2.9.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Stage 2: encrypted storage for the Gemini API key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")


    // Shizuku: control the phone without Accessibility, after Wireless debugging is paired
    implementation("dev.rikka.shizuku:api:13.1.5")

    // Offline speech recognition (Vosk) for the "Hey Jarvis" wake word. The models are chosen from the phone.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // On-device text recognition. The model is downloaded by Google Play services, so the APK stays small.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.2")
}
