package ai.wakey.android.agent

/**
 * Recognises requests that start by opening an app ("open Instagram and search for cats",
 * "YouTube kholo aur songs chalao") so the loop can open it without asking the model first.
 */
internal object OpeningStep {
    private const val JOIN = """(?:,\s*|\s+)(?:and|then|and then|aur|phir|&)\s+"""
    private val ENGLISH = Regex(
        """^(?:please\s+)?(?:open|launch|start)\s+(?:the\s+|my\s+)?(.+?)(?:\s+app)?$JOIN\S.*$""",
        RegexOption.IGNORE_CASE,
    )
    private val HINGLISH = Regex("""^(.+?)(?:\s+app)?\s+(?:kholo|khol do|open karo)$JOIN\S.*$""", RegexOption.IGNORE_CASE)

    /** The app to open first, or null when the request doesn't start with opening one. */
    fun appToOpen(goal: String): String? {
        val text = goal.trim().trimEnd('.', '!', '?', '।')
        val name = (ENGLISH.matchEntire(text) ?: HINGLISH.matchEntire(text))?.groupValues?.get(1)?.trim() ?: return null
        // Longer phrases are usually not app names ("open the email from Priya and reply").
        return name.takeIf { it.isNotEmpty() && it.split(Regex("\\s+")).size <= MAX_APP_WORDS }
    }

    private const val MAX_APP_WORDS = 3
}
