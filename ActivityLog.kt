package com.jarvis.ai.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Execution log: every command and action result, newest first.
 * Kept in memory only, so it clears when the app closes. Nothing is sent anywhere.
 */
object ActivityLog {

    data class Entry(
        val timeMillis: Long,
        val command: String,
        val action: String,
        val success: Boolean,
        val message: String
    )

    private const val MAX_ENTRIES = 100

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun add(command: String, action: String, success: Boolean, message: String) {
        val entry = Entry(System.currentTimeMillis(), command, action, success, message)
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
