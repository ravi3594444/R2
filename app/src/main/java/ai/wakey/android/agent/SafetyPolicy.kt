package ai.wakey.android.agent

/**
 * Decides which agent actions need the user's explicit approval: whatever the model marks
 * sensitive, plus anything whose target or typed text looks consequential (send, pay, delete, …).
 * The keyword check is a backstop for the model forgetting the flag, so it errs towards asking.
 */
internal object SafetyPolicy {

    /** The confirmation to ask before [action], or null if it can run straight away. */
    fun review(action: AgentAction.ScreenChanging, appLabel: String?): ConfirmationRequest? {
        val texts = when (action) {
            is AgentAction.Tap -> action.element?.labels().orEmpty() + listOfNotNull(action.target.label)
            is AgentAction.EnterText -> action.element?.labels().orEmpty() + action.text
            else -> emptyList()
        }
        if (!action.sensitive && texts.none(::isConsequential)) return null
        val inApp = appLabel?.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()
        val question = when (action) {
            is AgentAction.Tap -> "Tap “${action.element?.label() ?: action.target.label ?: "this item"}”$inApp?"
            is AgentAction.EnterText -> "Type “${action.text.ellipsize()}”${if (action.submit) " and submit it" else ""}$inApp?"
            is AgentAction.OpenApp -> "Open ${action.name}?"
            is AgentAction.Scroll -> "Scroll ${action.direction.name.lowercase()}$inApp?"
            is AgentAction.ScrollTo -> "Scroll to “${action.text.ellipsize()}”$inApp?"
            is AgentAction.TapPoint -> "Tap that spot on the screen$inApp?"
            is AgentAction.GoBack -> "Go back$inApp?"
            is AgentAction.GoHome -> "Go to the home screen?"
        }
        val detail = listOfNotNull(action.reason, (action as? AgentAction.EnterText)?.text?.let { "Text: $it" })
            .joinToString("\n")
            .ifEmpty { question.removeSuffix("?") }
        return ConfirmationRequest(question, detail)
    }

    /** True if [text] names an action with consequences outside the phone screen. */
    fun isConsequential(text: String): Boolean =
        LATIN_KEYWORDS.containsMatchIn(text) || DEVANAGARI_KEYWORDS.any { it in text }

    private fun String.ellipsize(max: Int = 60) = if (length <= max) this else take(max - 1).trimEnd() + "…"

    private val LATIN_KEYWORDS = Regex(
        "\\b(send|pay|payment|payments|buy|purchase|order|checkout|check out|place order|transfer|delete|" +
            "remove|uninstall|reset|erase|sign out|log out|logout|password|subscribe|unsubscribe|book|post|" +
            "share|call|dial|donate|publish|sell|bhejo|bhejein|bhejen|bhej do|kharido|kharidein|bhugtan|hatao|mitao)\\b",
        RegexOption.IGNORE_CASE,
    )

    // Devanagari has no \b in Java regex, so these are substring checks; stems cover inflections.
    private val DEVANAGARI_KEYWORDS = listOf(
        "भेज", "खरीद", "ख़रीद", "भुगतान", "पेमेंट", "डिलीट", "हटा", "मिटा", "कॉल", "ऑर्डर", "बुक कर", "शेयर",
        "पोस्ट", "पासवर्ड", "साइन आउट", "लॉग आउट", "सब्सक्राइब", "ट्रांसफर",
    )
}
