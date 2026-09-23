package ai.wakey.android.accessibility

import kotlin.math.abs

/** Finds elements by the label the model or user used for them. */
object ElementMatcher {
    private const val MIN_CONTAINS_CHARS = 3
    private const val TIER_EXACT = 0
    private const val TIER_CONTAINS = 1
    private const val TIER_WORDS = 2
    private const val TIER_CONTEXT = 3
    private val IME_ACTION_LABELS = setOf("enter", "search", "go", "done", "send", "next", "return", "submit")
    private val DASHES = Regex("[\\u2010-\\u2015\\u2212]")
    private val QUOTES = Regex("[\"“”„«»]")
    private val APOSTROPHES = Regex("[‘’`´]")
    private val EDGE_PUNCTUATION = Regex("^[\\s'.,:;!?()\\[\\]]+|[\\s'.,:;!?()\\[\\]]+$")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Best element for [query], matched case-insensitively on labels and hints: an exact label
     * beats one containing the query, and either beats a label whose words all appear in the query.
     * Elements that can be acted on come before plain text for the first two (merging already put
     * tappable rows' texts on the rows). Then enabled, primary-label, closest-length, reading order.
     */
    fun find(elements: List<ParsedElement>, query: String): ParsedElement? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        return elements
            .mapNotNull { e -> score(e, q)?.let { e to it } }
            .minWithOrNull(
                compareBy(
                    { it.second.rank },
                    { it.second.tier },
                    { !it.first.element.enabled },
                    { it.second.labelIndex },
                    { it.second.lengthGap },
                    { it.first.id },
                )
            )
            ?.first
    }

    /** True if [query] names [element] (exact, contained or word match; not just its nearby label). */
    fun matches(element: ParsedElement, query: String): Boolean {
        val q = normalize(query)
        return q.isNotEmpty() && score(element, q)?.let { it.tier <= TIER_WORDS } == true
    }

    /** Up to [limit] visible labels most similar to [query], actionable ones first on ties. */
    fun suggestions(elements: List<ParsedElement>, query: String, limit: Int = 4): List<String> {
        val q = normalize(query)
        return elements.asSequence()
            .flatMap { e -> (e.labels.take(2) + listOfNotNull(e.element.hint)).map { it to e } }
            .distinctBy { normalize(it.first) }
            .sortedWith(compareBy({ -similarity(q, normalize(it.first)) }, { !it.second.isActionable() }, { it.second.id }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** True for keyboard keys that submit the field (Gboard/Samsung label them "Search", "Go", …). */
    fun isImeActionKey(label: String?): Boolean = label != null && normalize(label) in IME_ACTION_LABELS

    /** Lowercased, quotes and dashes unified, edge punctuation dropped, spaces collapsed. */
    fun normalize(text: String): String = text.lowercase()
        .replace(QUOTES, "")
        .replace(APOSTROPHES, "'")
        .replace(DASHES, "-")
        .replace('\u00A0', ' ')
        .replace(WHITESPACE, " ")
        .replace(EDGE_PUNCTUATION, "")

    private class Score(val rank: Int, val tier: Int, val labelIndex: Int, val lengthGap: Int)

    private fun score(e: ParsedElement, q: String): Score? {
        val qWords = LabelSet.wordsOf(q)
        var tier = Int.MAX_VALUE
        var labelIndex = 0
        var lengthGap = 0
        (e.labels + listOfNotNull(e.element.hint)).forEachIndexed { index, raw ->
            val label = normalize(raw)
            val labelWords = LabelSet.wordsOf(label)
            val t = when {
                label == q -> TIER_EXACT
                q.length >= MIN_CONTAINS_CHARS && label.contains(q) -> TIER_CONTAINS
                q.length < MIN_CONTAINS_CHARS && LabelSet.containsRun(labelWords, qWords) -> TIER_CONTAINS
                label.length >= MIN_CONTAINS_CHARS && LabelSet.containsRun(qWords, labelWords) -> TIER_WORDS
                else -> return@forEachIndexed
            }
            if (t < tier) {
                tier = t
                labelIndex = index
                lengthGap = abs(label.length - q.length)
            }
        }
        if (tier == Int.MAX_VALUE) {
            if (e.context == null || normalize(e.context) != q) return null
            tier = TIER_CONTEXT
        }
        val rank = when {
            tier > TIER_CONTAINS -> tier
            e.isActionable() -> 0
            else -> 1
        }
        return Score(rank, tier, labelIndex, lengthGap)
    }

    private fun ParsedElement.isActionable() =
        element.clickable || element.editable || element.scrollable || element.checked != null

    /** Higher is closer: 1 minus the relative edit distance, boosted when the strings share a whole word. */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val distance = levenshtein(a, b)
        val ratio = 1.0 - distance.toDouble() / maxOf(a.length, b.length)
        val shared = LabelSet.wordsOf(a).toSet().intersect(LabelSet.wordsOf(b).toSet()).isNotEmpty()
        return if (shared) maxOf(ratio, 0.5) + 0.25 else ratio
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
