plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Chaquopy runs Python on the phone, for the coding study mode.
    id("com.chaquo.python") version "17.0.0" apply false
}
