package ai.wakey.android.agent

/**
 * Decides which agent actions need the user's explicit approval: whatever the model marks
 * sensitive, plus anything whose target or typed text looks consequential (send, pay, delete, …).
 * The keyword check is a backstop for the model forgetting the flag, so it errs towards asking.
 *
 * What the request itself asks for is not questioned again: "call mum" tapping Call, or "message
 * Priya that I'm late" tapping Send, runs straight away. Money, deleting and account changes
 * still get one confirmation, however the request was worded.
 */
internal object SafetyPolicy {

    /** The confirmation to ask before [action], or null if it can run straight away. [goal] is the user's request. */
    fun review(action: AgentAction.ScreenChanging, appLabel: String?, goal: String = ""): ConfirmationRequest? {
        val texts = when (action) {
            is AgentAction.Tap -> action.element?.labels().orEmpty() + listOfNotNull(action.target.label)
            is AgentAction.EnterText -> action.element?.labels().orEmpty() + action.text
            else -> emptyList()
        }
        if (!action.sensitive && texts.none(::isConsequential)) return null
        if (requested(goal, texts + listOfNotNull(action.reason), appLabel)) return null
        val inApp = appLabel?.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()
        val question = when (action) {
            is AgentAction.Tap -> "Tap “${action.element?.label() ?: action.target.label ?: "this item"}”$inApp?"
            is AgentAction.EnterText -> "Type “${action.text.ellipsize()}”${if (action.submit) " and submit it" else ""}$inApp?"
            is AgentAction.OpenApp -> "Open ${action.name}?"
            is AgentAction.OpenLink -> "Open ${action.link.target}?"
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

    /**
     * True when everything consequential about the action ([evidence]: its labels, typed text and
     * the model's reason) is of a kind the request explicitly asks for, and none of it touches money,
     * deleting or the account.
     */
    private fun requested(goal: String, evidence: List<String>, appLabel: String?): Boolean {
        if (goal.isBlank()) return false
        if ((evidence + goal).any(::isIrreversible) || appLabel?.let(MONEY_APPS::containsMatchIn) == true) return false
        val kinds = REQUESTABLE.filter { kind -> evidence.any(kind::does) }
        // The model flagged something it didn't name: better to ask.
        if (kinds.isEmpty()) return false
        return kinds.all { it.asks(goal) }
    }

    /** Actions with money, data or the account at stake: one confirmation even when asked for. */
    private fun isIrreversible(text: String): Boolean =
        IRREVERSIBLE.containsMatchIn(text) || DEVANAGARI_IRREVERSIBLE.any { it in text }

    /** An action kind the user can pre-approve by asking for it: what a request says, and what a control says. */
    private class Kind(asks: String, asksDevanagari: List<String>, does: String, doesDevanagari: List<String>) {
        private val asksRegex = Regex("\\b(?:$asks)\\b", RegexOption.IGNORE_CASE)
        private val doesRegex = Regex("\\b(?:$does)\\b", RegexOption.IGNORE_CASE)
        private val asksDevanagari = asksDevanagari
        private val doesDevanagari = doesDevanagari

        fun asks(goal: String) = asksRegex.containsMatchIn(goal) || asksDevanagari.any { it in goal }
        fun does(text: String) = doesRegex.containsMatchIn(text) || doesDevanagari.any { it in text }
    }

    private val REQUESTABLE = listOf(
        Kind(
            // Verbs only: "open WhatsApp and check messages" asks for no sending.
            asks = "send|text|message|reply|respond|bhejo|bhej|bhejein|bhejen|bhej do|likho|likh",
            asksDevanagari = listOf("भेज", "मैसेज", "मेसेज", "लिख"),
            does = "send|reply|bhejo|bhejein|bhejen|bhej do", doesDevanagari = listOf("भेज", "सेंड"),
        ),
        Kind(
            asks = "call|dial|ring|phone|video call", asksDevanagari = listOf("कॉल", "फोन", "फ़ोन"),
            does = "call|dial", doesDevanagari = listOf("कॉल", "डायल"),
        ),
        Kind(
            asks = "share|post|upload|publish|tweet", asksDevanagari = listOf("शेयर", "पोस्ट", "अपलोड"),
            does = "share|post|publish|upload", doesDevanagari = listOf("शेयर", "पोस्ट"),
        ),
        Kind(asks = "book|reserve", asksDevanagari = listOf("बुक"), does = "book|reserve", doesDevanagari = listOf("बुक")),
        Kind(asks = "subscribe", asksDevanagari = listOf("सब्सक्राइब"), does = "subscribe", doesDevanagari = listOf("सब्सक्राइब")),
    )

    private val IRREVERSIBLE = Regex(
        "\\b(pay|payment|payments|buy|purchase|order|checkout|check out|place order|transfer|donate|sell|money|rs|rupees?|" +
            "upi|amount|wallet|bank|paise|paisa|kharido|kharidein|bhugtan|delete|remove|uninstall|reset|erase|format|" +
            "sign out|log out|logout|password|unsubscribe|hatao|mitao|block|report)\\b|₹",
        RegexOption.IGNORE_CASE,
    )
    private val DEVANAGARI_IRREVERSIBLE = listOf(
        "खरीद", "ख़रीद", "भुगतान", "पेमेंट", "पैसे", "पैसा", "रुपये", "रुपए", "डिलीट", "हटा", "मिटा", "ऑर्डर", "पासवर्ड",
        "साइन आउट", "लॉग आउट", "ट्रांसफर", "बैंक",
    )

    /** Payment apps, where "Send" moves money. */
    private val MONEY_APPS = Regex("pay|paytm|phonepe|bhim|upi|bank|wallet|cred\\b|mobikwik|freecharge|zerodha|groww|razorpay", RegexOption.IGNORE_CASE)

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
