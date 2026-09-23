package ai.wakey.android.agent

import ai.wakey.android.accessibility.ScreenObservation

/**
 * Catches a premature finish: the request asked to open or find something, and the latest screen
 * still lists it as a tappable row instead of showing its page. Live runs showed the model stopping
 * at "Bluetooth is under Connected devices" despite the prompt rule, so the loop checks in code.
 */
internal object FinishCheck {
    private val CLAUSE_SPLIT = Regex("""\s*(?:,|\band\b|\bthen\b|\baur\b|\bphir\b)\s*""", RegexOption.IGNORE_CASE)
    private val NAVIGATION = Regex(
        """^(?:please\s+)?(?:find|open|go\s+to|show(?:\s+me)?|navigate\s+to|take\s+me\s+to)\s+(?:the\s+|my\s+)?(.+?)""" +
            """(?:\s+(?:settings?|page|screen|section|option|menu))?[.!?]*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Things the request asks to open, lower-cased, e.g. ["settings", "bluetooth"]. */
    fun targets(goal: String): List<String> = goal.trim().split(CLAUSE_SPLIT)
        .mapNotNull { NAVIGATION.matchEntire(it.trim())?.groupValues?.get(1)?.trim()?.lowercase() }
        .filter { it.length >= 3 }

    /** A correction for the model if a requested item is listed but not opened, else null. */
    fun unopenedTarget(goal: String, screen: ScreenObservation?): String? {
        screen ?: return null
        for (target in targets(goal)) {
            // A plain-text heading with the target's name means its page is already open.
            val onItsPage = screen.elements.any { !it.clickable && primaryLabel(it.text) == target }
            if (onItsPage) continue
            val row = screen.elements.firstOrNull {
                it.clickable && (primaryLabel(it.text) == target || it.description?.trim()?.lowercase() == target)
            } ?: continue
            val name = row.text?.substringBefore(" – ") ?: row.description ?: target
            return "Not finished: the request was to open “$name”, and [${row.id}] “$name” is only listed on this " +
                "screen. Tap it, check the new screen, then finish."
        }
        return null
    }

    private fun primaryLabel(text: String?): String? = text?.substringBefore(" – ")?.trim()?.lowercase()
}
