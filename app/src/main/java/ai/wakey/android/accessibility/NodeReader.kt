package ai.wakey.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Copies live accessibility nodes into [RawNode] trees, bounded in node count and depth, and keeps
 * each live node (indexed by [RawNode.key]) so actions can target it. Node queries are binder calls
 * into the other app, so run this off the main thread; [ensureActive] stops a cancelled walk.
 */
internal class NodeReader(
    private val maxNodes: Int = MAX_NODES,
    private val maxDepth: Int = MAX_DEPTH,
    private val ensureActive: () -> Unit = {},
) {
    val nodes = ArrayList<AccessibilityNodeInfo>()

    /** True if the node or depth budget cut the walk short. */
    var hitLimit = false
        private set

    fun read(root: AccessibilityNodeInfo): RawNode = copy(root, depth = 0)

    private fun copy(node: AccessibilityNodeInfo, depth: Int): RawNode {
        if (nodes.size % CANCEL_CHECK_INTERVAL == 0) ensureActive()
        val key = nodes.size
        nodes += node
        val children = ArrayList<RawNode>()
        for (i in 0 until node.childCount) {
            if (nodes.size >= maxNodes || depth >= maxDepth) {
                hitLimit = true
                break
            }
            val child = node.getChild(i) ?: continue
            // Offscreen pages and collapsed sections are skipped with their whole subtree.
            if (child.isVisibleToUser) children += copy(child, depth + 1)
        }
        return RawNode(
            key = key,
            className = node.className?.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            hint = node.hintText?.toString(),
            stateDescription = node.stateDescription?.toString(),
            roleDescription = node.extras?.getCharSequence(ROLE_DESCRIPTION_KEY)?.toString(),
            viewId = node.viewIdResourceName,
            bounds = node.screenBounds(),
            visible = node.isVisibleToUser,
            enabled = node.isEnabled,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            selected = node.isSelected,
            focused = node.isFocused,
            password = node.isPassword,
            showingHint = node.isShowingHintText,
            heading = node.isHeading,
            children = children,
        )
    }

    companion object {
        const val MAX_NODES = 1_500
        const val MAX_DEPTH = 60
        private const val CANCEL_CHECK_INTERVAL = 32

        /** Extras key AndroidX, Compose and Chrome use for role descriptions. */
        private const val ROLE_DESCRIPTION_KEY = "AccessibilityNodeInfo.roleDescription"
    }
}

/** One read of the screen: the parsed elements plus the live nodes they came from. */
internal class ScreenCapture(
    val packageName: String?,
    /** Topmost first; the last is the main window. */
    val windows: List<RawWindow>,
    val parsed: ParsedScreen,
    private val nodes: List<AccessibilityNodeInfo>,
    val keyboardOpen: Boolean,
    val wakeyActive: Boolean,
) {
    fun node(element: ParsedElement): AccessibilityNodeInfo? = nodes.getOrNull(element.key)

    fun element(id: Int): ParsedElement? = parsed.elements.getOrNull(id - 1)

    /** The main window's area, for gestures that need somewhere to swipe. */
    val mainBounds: NodeBounds? get() = windows.lastOrNull()?.root?.bounds?.takeUnless { it.isEmpty }
}

/** Reads the windows [WindowSelector] picks and parses them into at most [maxElements] elements. */
internal fun AccessibilityService.captureScreen(maxElements: Int, ensureActive: () -> Unit): ScreenCapture {
    val infos: List<AccessibilityWindowInfo> = windows.orEmpty()
    val sources: List<AccessibilityNodeInfo>
    val keyboardOpen: Boolean
    val wakeyActive: Boolean
    if (infos.isEmpty()) {
        // Window info can be briefly missing during transitions; the active window alone will do.
        val root = rootInActiveWindow
        wakeyActive = root?.packageName?.toString() == packageName
        keyboardOpen = false
        sources = listOfNotNull(root.takeUnless { wakeyActive })
    } else {
        val kinds = infos.map { kindOf(it.type) }
        // Each root is a binder call; the keyboard and overlays are never read, so skip theirs.
        val roots = infos.mapIndexed { i, w ->
            if (kinds[i] == WindowKind.Application || kinds[i] == WindowKind.System) w.root else null
        }
        val candidates = infos.mapIndexed { i, w ->
            WindowCandidate(kinds[i], w.layer, w.isActive, w.isFocused, roots[i]?.packageName?.toString())
        }
        val selection = WindowSelector.select(candidates, packageName)
        wakeyActive = selection.wakeyActive
        keyboardOpen = selection.keyboardOpen
        sources = selection.windows.mapNotNull { roots[it] }
    }
    val reader = NodeReader(ensureActive = ensureActive)
    val read = sources.map { RawWindow(it.packageName?.toString(), reader.read(it)) }
    return ScreenCapture(
        packageName = read.lastOrNull()?.packageName ?: packageName.takeIf { wakeyActive },
        windows = read,
        parsed = ScreenParser.parse(read, maxElements, reader.hitLimit),
        nodes = reader.nodes,
        keyboardOpen = keyboardOpen,
        wakeyActive = wakeyActive,
    )
}

private fun kindOf(type: Int): WindowKind = when (type) {
    AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.Application
    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.InputMethod
    AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.System
    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowKind.AccessibilityOverlay
    else -> WindowKind.Other
}

internal fun AccessibilityNodeInfo.screenBounds(): NodeBounds {
    val rect = Rect().also(::getBoundsInScreen)
    return NodeBounds(rect.left, rect.top, rect.right, rect.bottom)
}

/** This node or the nearest ancestor matching [predicate], at most [maxHops] levels up. */
internal fun AccessibilityNodeInfo.selfOrAncestor(
    maxHops: Int = 12,
    predicate: (AccessibilityNodeInfo) -> Boolean,
): AccessibilityNodeInfo? {
    var node: AccessibilityNodeInfo? = this
    repeat(maxHops + 1) {
        val current = node ?: return null
        if (predicate(current)) return current
        node = current.parent
    }
    return null
}

/** Breadth-first search of this node and its visible descendants, visiting at most [limit] nodes. */
internal fun AccessibilityNodeInfo.findFirst(
    limit: Int = 200,
    predicate: (AccessibilityNodeInfo) -> Boolean,
): AccessibilityNodeInfo? {
    val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(this@findFirst) }
    var visited = 0
    while (queue.isNotEmpty() && visited++ < limit) {
        val node = queue.removeFirst()
        if (predicate(node)) return node
        for (i in 0 until node.childCount) node.getChild(i)?.takeIf { it.isVisibleToUser }?.let(queue::addLast)
    }
    return null
}
