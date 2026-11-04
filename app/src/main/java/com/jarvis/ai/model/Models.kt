package com.jarvis.ai.model

/** High-level state of the assistant. Drives the orb animation and status text. */
enum class JarvisState {
    IDLE,
    LISTENING,
    THINKING,
    EXECUTING,
    SPEAKING,
    ERROR
}

enum class Sender { USER, JARVIS }

data class ChatMessage(
    val id: Long,
    val sender: Sender,
    val text: String
)

/** Languages the recognizer and TTS engine are configured for. */
enum class SpeechLanguage(val tag: String, val label: String) {
    ENGLISH_INDIA("en-IN", "English awaaz (phone)"),
    HINDI_INDIA("hi-IN", "Hindi awaaz (phone)")
}
