package com.jarvis.ai.core

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The answer to a "neural waves" request. While it is set, the home screen shows the 3D network
 * with the answer under it, instead of the orb. Cleared by the user's button.
 */
object NeuralView {
    val answer = MutableStateFlow<String?>(null)
}
