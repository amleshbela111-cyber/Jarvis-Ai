package com.jarvis.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Result of typing into a field. */
data class TextEntryResult(val ok: Boolean, val message: String)

/**
 * Accessibility service for on-screen actions. It reads the UI tree on demand
 * and acts only when [AccessibilityActions] asks it to. It does not react to
 * events on its own, and it never fills password fields.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    /** Events are ignored. The screen is read only when a voice command needs it. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /** Required override. Jarvis has no ongoing feedback to interrupt. */
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---------- Node actions (preferred) ----------

    /** Clicks the nearest clickable element whose text, description, or hint contains [label]. */
    fun clickByLabel(label: String): Boolean = withNodes { nodes ->
        val target = nodes.firstOrNull { it.isVisibleToUser && labelMatches(it, label) }
            ?: return@withNodes false
        val clickable = clickableAncestorOrSelf(target) ?: return@withNodes false
        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Scrolls the first visible scrollable area. "down" reveals content below. */
    fun scroll(direction: String): Boolean = withNodes { nodes ->
        val scrollable = nodes.firstOrNull { it.isScrollable && it.isVisibleToUser }
            ?: return@withNodes false
        val action = if (direction == "down") {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        scrollable.performAction(action)
    }

    /** Types [text] into the field named by [target], or into the focused field when [target] is blank. */
    fun inputText(target: String?, text: String): TextEntryResult = withNodes { nodes ->
        val wanted = target?.trim().orEmpty()
        val node = (
            if (wanted.isEmpty()) {
                rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: nodes.firstOrNull { it.isEditable && it.isFocused && it.isVisibleToUser }
            } else {
                nodes.firstOrNull { it.isEditable && it.isVisibleToUser && labelMatches(it, wanted) }
            }
            ) ?: return@withNodes TextEntryResult(
            false,
            "No matching text field found. Tell me which field, or tap it first."
        )

        if (node.isPassword) {
            return@withNodes TextEntryResult(false, "Password fields are not filled by Jarvis.")
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return@withNodes TextEntryResult(false, "The field did not accept the text.")
        }
        node.refresh()
        if (node.text?.toString() == text) {
            TextEntryResult(true, "Typed the text.")
        } else {
            TextEntryResult(false, "The text did not appear in the field.")
        }
    }

    /** Visible, non-password text on screen, one item per line, capped at [limit] characters. */
    fun readVisibleText(limit: Int): String = withNodes { nodes ->
        nodes.asSequence()
            .filter { it.isVisibleToUser && !it.isPassword }
            .mapNotNull { node ->
                (node.text ?: node.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }
            .distinct()
            .joinToString("\n")
            .take(limit)
    }

    // ---------- Global actions ----------

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    // ---------- Gesture (fallback when node actions are not enough) ----------

    /** Swipes across the middle of the screen. Returns true only when Android reports the gesture completed. */
    suspend fun swipe(direction: String): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val dx = width * 0.3f
        val dy = height * 0.3f

        // [startX, startY, endX, endY]: the finger moves in the named direction.
        val coords = when (direction) {
            "up" -> floatArrayOf(cx, cy + dy, cx, cy - dy)
            "down" -> floatArrayOf(cx, cy - dy, cx, cy + dy)
            "left" -> floatArrayOf(cx + dx, cy, cx - dx, cy)
            else -> floatArrayOf(cx - dx, cy, cx + dx, cy)
        }

        val path = Path().apply {
            moveTo(coords[0], coords[1])
            lineTo(coords[2], coords[3])
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SWIPE_DURATION_MS))
            .build()

        return suspendCancellableCoroutine { continuation ->
            val dispatched = dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }
    }

    // ---------- Helpers ----------

    private fun labelMatches(node: AccessibilityNodeInfo, label: String): Boolean {
        return listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.hintText?.toString()
        ).any { it.contains(label, ignoreCase = true) }
    }

    /** Walks up a few levels to find the element that actually receives clicks. */
    private fun clickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_PARENT_DEPTH) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * Collects every node in the active window, runs [block], then recycles the nodes.
     * Nodes must not escape [block]; the caller gets plain values back.
     */
    private fun <T> withNodes(block: (List<AccessibilityNodeInfo>) -> T): T {
        val root = rootInActiveWindow ?: return block(emptyList())
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            nodes += node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        try {
            return block(nodes)
        } finally {
            nodes.forEach { recycleNode(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo) {
        node.recycle()
    }

    companion object {
        /** Set while the service is connected. Null means the user has not enabled it, or it was stopped. */
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        private const val SWIPE_DURATION_MS = 300L
        private const val MAX_PARENT_DEPTH = 6
    }
}
