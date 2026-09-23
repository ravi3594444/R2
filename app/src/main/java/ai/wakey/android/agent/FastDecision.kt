package ai.wakey.android.agent

import ai.wakey.android.accessibility.ElementTarget
import ai.wakey.android.accessibility.ScreenElement
import ai.wakey.android.accessibility.ScreenObservation
import ai.wakey.android.accessibility.ScrollDirection
import ai.wakey.android.llm.Decision
import ai.wakey.android.llm.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the current screen into a closed set of next actions for a decision model (Jev) and maps
 * its choice back to an [AgentAction]. Only confident choices are acted on; anything else goes to
 * the LLM, which can also type free text, read screenshots and answer questions.
 */
internal object FastDecision {
    const val INSTRUCTIONS =
        "Which single action should the phone agent take next to accomplish the task? " +
            "Choose done only if the requested page or result is already showing on this screen. " +
            "Choose unsure if no option clearly moves the task forward."

    private const val DONE = "done"
    private const val UNSURE = "unsure"
    private const val SEARCH = "search"
    private const val SCROLL_DOWN = "scroll_down"
    private const val GO_BACK = "go_back"
    private const val TAP_PREFIX = "tap_"
    private const val MAX_TAP_OPTIONS = 30
    private const val MAX_RECENT = 6

    /** Thresholds on Jev's calibrated confidence; "done" and "back" are costlier to get wrong. */
    const val MIN_ACT_CONFIDENCE = 0.70
    const val MIN_DONE_CONFIDENCE = 0.85
    const val MIN_BACK_CONFIDENCE = 0.80

    /**
     * Live runs had the right tap on top every time, often at 0.5–0.7 confidence with the runner-up
     * far behind. A clear winner is acted on too; "done" and "back" still need their thresholds.
     */
    const val MIN_WINNER_PROBABILITY = 0.55
    const val MIN_WINNER_MARGIN = 0.20

    sealed interface Move {
        /** [confirmText] visible on the next screen (outside inputs) means the task is done. */
        data class Act(val action: AgentAction.ScreenChanging, val call: ToolCall, val confirmText: String? = null) : Move
        data object Done : Move
    }

    class Plan(val state: String, val options: Map<String, String>, internal val moves: Map<String, Move>)

    private val UI_WORDS = Regex(
        "\\b(open|find|go to|search|look up|tap|click|press|turn|switch|show|play|scroll|launch|start|settings|app|send|reply|type|" +
            "kholo|khol|dikhao|chalao|dhundo|dhoondo|lagao|band)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val DEVANAGARI_UI_WORDS = listOf("खोल", "दिखा", "चला", "ढूंढ", "सर्च", "सेटिंग")

    /** Requests that operate the phone's UI; questions and chat go straight to the LLM. */
    fun suitsGoal(goal: String): Boolean = UI_WORDS.containsMatchIn(goal) || DEVANAGARI_UI_WORDS.any { it in goal }

    // "find"/"look for" mean navigating to something on screen, not typing a query.
    private val SEARCH_QUERY = Regex(
        "\\b(?:search|look up)\\s+(?:for\\s+)?(.+?)(?:\\s+(?:on|in)\\s+(?:the\\s+)?[\\w .]+(?:app)?)?[.!?]*$",
        RegexOption.IGNORE_CASE,
    )
    private val HINGLISH_QUERY = Regex("^(.+?)\\s+(?:search|dhundo|dhoondo)\\s+karo[.!?]*$", RegexOption.IGNORE_CASE)

    /** The words to type for "…search for cats" / "…cats search karo", or null. */
    fun searchQuery(goal: String): String? {
        val tail = goal.trim().split(Regex("\\s+(?:and|then|aur|phir)\\s+", RegexOption.IGNORE_CASE)).last()
        val match = HINGLISH_QUERY.matchEntire(tail)?.groupValues?.get(1) ?: SEARCH_QUERY.find(tail)?.groupValues?.get(1)
        return match?.trim()?.trim('"', '“', '”', '\'')?.takeIf { it.isNotEmpty() && it.length <= 80 }
    }

    fun plan(goal: String, screen: ScreenObservation, recent: List<String>, callId: String): Plan {
        val options = LinkedHashMap<String, String>()
        val moves = HashMap<String, Move>()
        searchMove(goal, screen, callId)?.let { (description, move) ->
            options[SEARCH] = description
            moves[SEARCH] = move
        }
        screen.elements.filter { it.clickable && it.enabled && !it.editable && it.label() != null }
            .take(MAX_TAP_OPTIONS)
            .forEach { element ->
                val key = TAP_PREFIX + element.id
                options[key] = "Tap [${element.id}] ${element.role} “${element.label()}”"
                val args = JSONObject().put("element_id", element.id).toString()
                moves[key] = Move.Act(
                    AgentAction.Tap(ElementTarget(id = element.id), element, sensitive = false, reason = null),
                    ToolCall(callId, AgentTools.TAP, args),
                )
            }
        options[SCROLL_DOWN] = "Scroll down to see more of this screen"
        moves[SCROLL_DOWN] = Move.Act(
            AgentAction.Scroll(ScrollDirection.Down, null, sensitive = false, reason = null),
            ToolCall(callId, AgentTools.SCROLL, JSONObject().put("direction", "down").toString()),
        )
        options[GO_BACK] = "Go back to the previous screen"
        moves[GO_BACK] = Move.Act(
            AgentAction.GoBack(sensitive = false, reason = null),
            ToolCall(callId, AgentTools.GO_BACK, "{}"),
        )
        options[DONE] = "The task is finished: the exact page or result it asks for is open on this screen (only listed is not finished)"
        moves[DONE] = Move.Done
        options[UNSURE] = "None of these clearly moves the task forward"

        val state = JSONObject()
            .put("task", goal)
            .put("app", screen.appLabel ?: screen.packageName ?: "unknown")
            .put("screen", JSONArray(screen.text.lines().filter { it.isNotBlank() }))
            .put("previous_actions", JSONArray(recent.takeLast(MAX_RECENT)))
            .toString()
        return Plan(state, options, moves)
    }

    /** The move to make for [decision], or null when it is unsure or not confident enough. */
    fun interpret(plan: Plan, decision: Decision): Move? {
        val move = plan.moves[decision.choice] ?: return null
        return when (decision.choice) {
            DONE -> move.takeIf { decision.confidence >= MIN_DONE_CONFIDENCE }
            GO_BACK -> move.takeIf { decision.confidence >= MIN_BACK_CONFIDENCE }
            else -> move.takeIf { decision.confidence >= MIN_ACT_CONFIDENCE || isClearWinner(decision) }
        }
    }

    private fun isClearWinner(decision: Decision): Boolean {
        val ranked = decision.probabilities.values.sortedDescending()
        val top = decision.probabilities[decision.choice] ?: return false
        val runnerUp = ranked.getOrElse(1) { 0.0 }
        return top == ranked.first() && top >= MIN_WINNER_PROBABILITY && top - runnerUp >= MIN_WINNER_MARGIN
    }

    /** Types the request's query into the search field, or taps a search icon/box and types there. */
    private fun searchMove(goal: String, screen: ScreenObservation, callId: String): Pair<String, Move>? {
        val query = searchQuery(goal) ?: return null
        // Already typed: offering it again would loop instead of opening a result.
        if (screen.elements.any { it.editable && it.text?.contains(query, ignoreCase = true) == true }) return null
        val target = screen.elements.firstOrNull { it.editable && it.enabled }
            ?: screen.elements.firstOrNull { it.enabled && it.clickable && it.mentionsSearch() }
            ?: return null
        val args = JSONObject().put("text", query).put("element_id", target.id).put("submit", true).toString()
        val move = Move.Act(
            AgentAction.EnterText(query, ElementTarget(id = target.id), target, submit = true, sensitive = false, reason = null),
            ToolCall(callId, AgentTools.ENTER_TEXT, args),
            confirmText = query,
        )
        return "Search for “$query” using [${target.id}] “${target.label() ?: "search"}”" to move
    }

    private fun ScreenElement.mentionsSearch(): Boolean =
        (labels() + listOfNotNull(viewId)).any { it.contains("search", ignoreCase = true) || it.contains("खोज") }
}
