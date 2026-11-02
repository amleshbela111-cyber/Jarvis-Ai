package com.jarvis.ai.accessibility

/** One item on screen, read from a uiautomator dump. Coordinates are the centre point. */
data class UiNode(
    val text: String,
    val desc: String,
    val cls: String,
    val clickable: Boolean,
    val cx: Int,
    val cy: Int
)

/** Reads the screen from a uiautomator XML dump, so Jarvis can find items without Accessibility. */
object ScreenTree {

    private val nodeTag = Regex("<node\\b([^>]*)>")
    private val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val bounds = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

    private val ordinals = mapOf(
        "first" to 0, "1st" to 0, "pehla" to 0, "pehli" to 0,
        "second" to 1, "2nd" to 1, "doosra" to 1, "doosri" to 1,
        "third" to 2, "3rd" to 2, "teesra" to 2, "teesri" to 2,
        "fourth" to 3, "4th" to 3, "chautha" to 3,
        "fifth" to 4, "5th" to 4
    )

    private val ignored = setOf(
        "click", "tap", "on", "the", "button", "karo", "kar", "do", "par", "pe", "ko", "dabao"
    )

    fun parse(xml: String): List<UiNode> = nodeTag.findAll(xml).mapNotNull { match ->
        val a = attr.findAll(match.groupValues[1]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        val b = bounds.find(a["bounds"].orEmpty()) ?: return@mapNotNull null
        val (x1, y1, x2, y2) = b.destructured
        val left = x1.toInt()
        val top = y1.toInt()
        val right = x2.toInt()
        val bottom = y2.toInt()
        if (right <= left || bottom <= top) return@mapNotNull null
        UiNode(
            text = a["text"].orEmpty(),
            desc = a["content-desc"].orEmpty(),
            cls = a["class"].orEmpty(),
            clickable = a["clickable"] == "true",
            cx = (left + right) / 2,
            cy = (top + bottom) / 2
        )
    }.toList()

    /** Finds the nth matching clickable item. "first short" means the first item containing "short". */
    fun find(nodes: List<UiNode>, label: String): UiNode? {
        val (ordinal, words) = parseWords(label)
        return nodes.filter { n ->
            if (words.isEmpty()) {
                n.clickable && (n.text.isNotBlank() || n.desc.isNotBlank())
            } else {
                words.all { w -> (n.text + " " + n.desc).contains(w, ignoreCase = true) }
            }
        }.getOrNull(ordinal)
    }

    /** Finds a text field (EditText) whose text or description matches [label]. */
    fun findField(nodes: List<UiNode>, label: String): UiNode? {
        val words = parseWords(label).second
        return nodes.filter { n ->
            n.cls.contains("EditText") && words.all { w -> (n.text + " " + n.desc).contains(w, ignoreCase = true) }
        }.firstOrNull()
    }

    /** Visible text on screen, one item per line, capped at [limit] characters. */
    fun readable(nodes: List<UiNode>, limit: Int): String = nodes.asSequence()
        .map { if (it.text.isNotBlank()) it.text.trim() else it.desc.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("\n")
        .take(limit)

    private fun parseWords(label: String): Pair<Int, List<String>> {
        var ordinal = 0
        val words = label.trim().lowercase().split(Regex("\\s+")).filter { word ->
            when {
                word.isEmpty() || word in ignored -> false
                ordinals.containsKey(word) -> {
                    ordinal = ordinals.getValue(word)
                    false
                }
                else -> true
            }
        }
        return ordinal to words
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#10;", "\n")
        .replace("&amp;", "&")
}
