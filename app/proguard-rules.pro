# Shizuku loads ShellService by name, so it must be kept.
-keep class com.jarvis.ai.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }

# Vosk and JNA use native code and reflection; keep them so release builds still work.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-dontwarn java.awt.**

# The coding study sandbox calls Android.done() from JavaScript, so its bridge method must keep its name.
-keepclassmembers class com.jarvis.ai.study.JsSandbox$Bridge { *; }
