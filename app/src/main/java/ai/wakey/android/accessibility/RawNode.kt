package ai.wakey.android.accessibility

/** Screen rectangle in pixels. */
data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height
    val isEmpty: Boolean get() = width == 0 || height == 0
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    companion object {
        val EMPTY = NodeBounds(0, 0, 0, 0)
    }
}

/**
 * Plain copy of one accessibility node and its visible subtree, so screen parsing can be unit
 * tested without Android. [key] indexes the live node the reader kept for actions.
 */
data class RawNode(
    val key: Int = -1,
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val stateDescription: String? = null,
    /** AndroidX/Compose/Chrome role description extra, e.g. "Tab" or "Switch". */
    val roleDescription: String? = null,
    val viewId: String? = null,
    val bounds: NodeBounds = NodeBounds.EMPTY,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val focused: Boolean = false,
    val password: Boolean = false,
    /** True when [text] is the hint shown in an empty field rather than its content. */
    val showingHint: Boolean = false,
    val heading: Boolean = false,
    val children: List<RawNode> = emptyList(),
)

/** One window's tree. [packageName] is the owning app. */
data class RawWindow(val packageName: String?, val root: RawNode)
