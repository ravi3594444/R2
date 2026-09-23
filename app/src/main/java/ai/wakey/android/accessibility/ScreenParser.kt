package ai.wakey.android.accessibility

/** One element of a parsed screen, with what Wakey needs to act on it and match it by label. */
data class ParsedElement(
    val element: ScreenElement,
    /** [RawNode.key] of the node to act on. */
    val key: Int,
    /** Visible labels, primary first: the node's own text/description, then merged child labels. */
    val labels: List<String>,
    /** A nearby label for controls that have none of their own (e.g. an unlabeled switch in a row). */
    val context: String?,
    val selected: Boolean,
    val password: Boolean,
    /** Index of the source window in the list given to [ScreenParser.parse]. */
    val window: Int,
) {
    val id: Int get() = element.id

    /** Best short name for messages: the primary label, else the hint, else the nearby label. */
    val displayLabel: String? get() = labels.firstOrNull() ?: element.hint ?: context
}

/** Result of [ScreenParser.parse]: numbered elements plus what couldn't be shown or read. */
class ParsedScreen(
    val elements: List<ParsedElement>,
    /** More elements (or nodes) existed than were kept. */
    val truncated: Boolean,
    /** A large label-less surface (canvas, video, unexposed web content) covers part of the screen. */
    val hasUnreadableArea: Boolean,
)

/**
 * Turns raw accessibility trees into the numbered elements the agent sees.
 *
 * Clickable rows whose text lives in child views (a Settings entry with a title and summary) become
 * one element carrying the children's labels, and those children are not listed again. Rows with
 * many child labels (feed cards) are left unmerged so their texts stay individually readable.
 */
object ScreenParser {
    const val MAX_LABEL_CHARS = 80
    private const val MAX_MERGED_LABELS = 4
    private const val CONTEXT_SEARCH_DEPTH = 3
    private const val UNREADABLE_AREA_FRACTION = 0.3
    private val STATE_WORDS = setOf("on", "off", "checked", "unchecked", "not checked", "selected", "not selected")
    private val INVISIBLE_CHARS = Regex("[\\u200B-\\u200D\\uFEFF]")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Parses [windows] (topmost first) into at most [maxElements] elements with ids 1..N in reading
     * order. When over the limit, actionable elements are kept before plain text.
     */
    fun parse(windows: List<RawWindow>, maxElements: Int, readTruncated: Boolean = false): ParsedScreen {
        val candidates = mutableListOf<Candidate>()
        windows.forEachIndexed { index, window -> Walker(index, candidates).visit(window.root, parent = null) }
        val limit = maxElements.coerceAtLeast(1)
        val kept = if (candidates.size <= limit) candidates else prioritise(candidates, limit)
        return ParsedScreen(
            elements = kept.mapIndexed { i, candidate -> candidate.toElement(i + 1) },
            truncated = readTruncated || kept.size < candidates.size,
            hasUnreadableArea = windows.any { hasUnreadableSurface(it.root) },
        )
    }

    /** Role name from the node's class, role description and flags. */
    fun roleOf(node: RawNode): String {
        val role = explicitRole(node)
        return when {
            role != null && role != "text" -> role
            node.heading -> "heading"
            role != null -> role
            node.checkable -> "checkbox"
            node.clickable || node.longClickable -> "item"
            node.scrollable -> "list"
            else -> "text"
        }
    }

    /** Whitespace-collapsed, length-capped label, or null if blank. */
    fun cleanLabel(raw: CharSequence?): String? {
        if (raw == null) return null
        val collapsed = raw.toString().replace(INVISIBLE_CHARS, "").replace(WHITESPACE, " ").trim()
        if (collapsed.isEmpty()) return null
        return if (collapsed.length <= MAX_LABEL_CHARS) collapsed else collapsed.take(MAX_LABEL_CHARS - 1).trimEnd() + "…"
    }

    /** Role stated by the node itself (editable flag, role description or widget class), if any. */
    private fun explicitRole(node: RawNode): String? {
        if (node.editable) return "input"
        return roleFromDescription(node.roleDescription)
            ?: roleFromClass(node.className.orEmpty().substringAfterLast('.').substringAfterLast('$'))
    }

    private fun roleFromClass(name: String): String? = when {
        name.endsWith("EditText") || name.endsWith("AutoCompleteTextView") -> "input"
        name.endsWith("Switch") || name.endsWith("SwitchCompat") || name.endsWith("SwitchMaterial") ||
            name.endsWith("ToggleButton") -> "switch"
        name.endsWith("CheckBox") || name.endsWith("CheckedTextView") -> "checkbox"
        name.endsWith("RadioButton") -> "radio"
        name.endsWith("Spinner") -> "dropdown"
        name.endsWith("SeekBar") || name.endsWith("RatingBar") || name == "Slider" -> "slider"
        name.endsWith("ProgressBar") -> "progress"
        name == "Tab" || name.endsWith("TabView") || name == "TabWidget" -> "tab"
        name.endsWith("RecyclerView") || name.endsWith("ListView") || name.endsWith("GridView") ||
            name.endsWith("ScrollView") || name.startsWith("ViewPager") -> "list"
        name.endsWith("WebView") -> "web"
        name.endsWith("Button") -> "button"
        name.endsWith("ImageView") || name == "Image" -> "image"
        name.endsWith("TextView") -> "text"
        else -> null
    }

    /** English role descriptions set by AndroidX, Compose and Chrome; other languages fall back to the class. */
    private fun roleFromDescription(description: String?): String? {
        val d = description?.trim()?.lowercase() ?: return null
        return when {
            d == "tab" -> "tab"
            d == "button" -> "button"
            d == "switch" || d == "toggle" || d == "toggle button" -> "switch"
            d == "checkbox" || d == "check box" -> "checkbox"
            d == "radio button" -> "radio"
            d == "link" -> "link"
            d == "heading" || d.startsWith("heading ") -> "heading"
            d == "image" || d == "graphic" -> "image"
            d == "drop down list" || d == "dropdown list" || d == "drop-down list" || d == "combo box" -> "dropdown"
            d == "slider" -> "slider"
            else -> null
        }
    }

    /** The node's own labels: text (unless it is a field value or hint), description, extra state. */
    private fun ownLabels(node: RawNode): List<String> = LabelSet().apply {
        if (!node.editable && !node.password && !node.showingHint) add(node.text)
        add(node.contentDescription)
        if (!node.editable) {
            val state = cleanLabel(node.stateDescription)
            if (state != null && !(node.checkable && state.lowercase() in STATE_WORDS)) add(state)
        }
    }.items

    private fun RawNode.independentlyActionable() = clickable || longClickable || editable || scrollable
    private fun RawNode.checkedOrNull(): Boolean? = if (checkable) checked else null

    private class Candidate(
        val node: RawNode,
        val window: Int,
        val role: String,
        val labels: List<String>,
        val checked: Boolean?,
        val context: String?,
    ) {
        val actionable: Boolean get() = node.independentlyActionable() || checked != null

        fun toElement(id: Int): ParsedElement {
            val description = cleanLabel(node.contentDescription)
            val value = if (node.editable && !node.password && !node.showingHint) cleanLabel(node.text) else null
            val hint = (cleanLabel(node.hint) ?: if (node.showingHint) cleanLabel(node.text) else null)
                ?.takeIf { h -> labels.none { it.equals(h, ignoreCase = true) } }
            val text = if (node.editable) value else labels.filter { it != description }.joinToString(" – ").ifEmpty { null }
            val element = ScreenElement(
                id = id,
                role = role,
                text = text,
                description = description,
                hint = hint,
                viewId = node.viewId,
                clickable = node.clickable,
                editable = node.editable,
                scrollable = node.scrollable,
                checked = checked,
                enabled = node.enabled,
                focused = node.focused,
                left = node.bounds.left,
                top = node.bounds.top,
                right = node.bounds.right,
                bottom = node.bounds.bottom,
            )
            return ParsedElement(element, node.key, labels, context, node.selected, node.password, window)
        }
    }

    private class Merge(
        val labels: List<String>,
        val checked: Boolean?,
        val checkRole: String?,
        /** Actionable descendants listed as their own elements, with their parents. */
        val separate: List<Pair<RawNode, RawNode>>,
    )

    private class Walker(private val window: Int, private val out: MutableList<Candidate>) {
        fun visit(node: RawNode, parent: RawNode?) {
            if (!node.visible) return
            if ((node.clickable || node.longClickable) && !node.editable && !node.scrollable) {
                val merge = merge(node)
                if (merge != null) {
                    emit(node, parent, merge.labels, node.checkedOrNull() ?: merge.checked, merge.checkRole)
                    merge.separate.forEach { (child, childParent) -> visit(child, childParent) }
                    return
                }
            }
            val labels = ownLabels(node)
            if (labels.isNotEmpty() || node.independentlyActionable() || node.checkable || cleanLabel(node.hint) != null) {
                emit(node, parent, labels, node.checkedOrNull(), checkRole = null)
            }
            node.children.forEach { visit(it, node) }
        }

        private fun emit(node: RawNode, parent: RawNode?, labels: List<String>, checked: Boolean?, checkRole: String?) {
            // A plain row that absorbed a switch or checkbox is, to the user, that switch.
            val role = if (checkRole != null && explicitRole(node).let { it == null || it == "text" }) checkRole else roleOf(node)
            val unnamed = labels.isEmpty() && cleanLabel(node.hint) == null && !(node.editable && node.showingHint)
            val context = if (unnamed) firstLabelBelow(node, CONTEXT_SEARCH_DEPTH) ?: siblingLabel(node, parent) else null
            out += Candidate(node, window, role, labels, checked, context)
        }

        /** Absorbs non-actionable descendants' labels into [container]; null if there are too many. */
        private fun merge(container: RawNode): Merge? {
            val labels = LabelSet().apply { ownLabels(container).forEach(::add) }
            val separate = mutableListOf<Pair<RawNode, RawNode>>()
            var absorbed = 0
            var checked: Boolean? = null
            var checkRole: String? = null
            fun absorb(node: RawNode) {
                for (child in node.children) {
                    if (!child.visible) continue
                    if (child.independentlyActionable()) {
                        separate += child to node
                        continue
                    }
                    ownLabels(child).forEach { if (labels.add(it)) absorbed++ }
                    if (child.checkable && checked == null) {
                        checked = child.checked
                        checkRole = roleOf(child)
                    }
                    absorb(child)
                }
            }
            absorb(container)
            return if (absorbed > MAX_MERGED_LABELS) null else Merge(labels.items, checked, checkRole, separate)
        }

        private fun firstLabelBelow(node: RawNode, depth: Int): String? {
            if (depth == 0) return null
            for (child in node.children) {
                if (!child.visible) continue
                val label = ownLabels(child).firstOrNull() ?: firstLabelBelow(child, depth - 1)
                if (label != null) return label
            }
            return null
        }

        private fun siblingLabel(node: RawNode, parent: RawNode?): String? =
            parent?.children?.asSequence()
                ?.filter { it !== node && it.visible }
                ?.mapNotNull { ownLabels(it).firstOrNull() ?: firstLabelBelow(it, CONTEXT_SEARCH_DEPTH) }
                ?.firstOrNull()
    }

    private fun prioritise(candidates: List<Candidate>, limit: Int): List<Candidate> {
        val keep = BooleanArray(candidates.size)
        var slots = limit
        for (pass in 0..1) {
            for (i in candidates.indices) {
                if (slots == 0) break
                if (!keep[i] && (pass == 1 || candidates[i].actionable)) {
                    keep[i] = true
                    slots--
                }
            }
        }
        return candidates.filterIndexed { i, _ -> keep[i] }
    }

    private fun hasUnreadableSurface(root: RawNode): Boolean {
        val minArea = root.bounds.area * UNREADABLE_AREA_FRACTION
        if (minArea <= 0) return false
        fun check(node: RawNode): Boolean {
            if (!node.visible) return false
            val children = node.children.filter { it.visible }
            if (children.isNotEmpty()) return children.any(::check)
            return !node.scrollable && !node.editable && ownLabels(node).isEmpty() && node.bounds.area >= minArea
        }
        return check(root)
    }
}

/**
 * Ordered, de-duplicated labels. A label whose words already appear, in order, inside an earlier
 * label is dropped ("Bluetooth" after "Bluetooth, On"), but "On" is not hidden by "Connected".
 */
internal class LabelSet {
    val items = mutableListOf<String>()
    private val words = mutableListOf<List<String>>()

    fun add(raw: String?): Boolean {
        val label = ScreenParser.cleanLabel(raw) ?: return false
        val labelWords = wordsOf(label)
        val covered = if (labelWords.isEmpty()) {
            items.any { it == label }
        } else {
            words.any { containsRun(it, labelWords) }
        }
        if (covered) return false
        items += label
        words += labelWords
        return true
    }

    companion object {
        fun wordsOf(text: String): List<String> =
            text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

        /** True if [needle] occurs as a contiguous run of whole words in [haystack]. */
        fun containsRun(haystack: List<String>, needle: List<String>): Boolean {
            if (needle.isEmpty() || needle.size > haystack.size) return needle.isEmpty()
            return (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }
        }

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    }
}
