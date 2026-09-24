package ai.wakey.android.agent

import ai.wakey.android.accessibility.ElementTarget
import ai.wakey.android.accessibility.ScreenElement
import ai.wakey.android.accessibility.ScreenObservation
import ai.wakey.android.accessibility.ScrollDirection
import ai.wakey.android.llm.ToolCall
import ai.wakey.android.llm.ToolSpec
import org.json.JSONException
import org.json.JSONObject

/** How much of the phone the agent can see and control during a run. */
internal enum class ScreenAccess { Available, Unavailable, Locked }

/** A tool call that passed validation against the latest screen. */
internal sealed interface AgentAction {
    /** Set by the model: the action sends, pays, deletes, posts, calls or changes sensitive settings. */
    val sensitive: Boolean get() = false
    val reason: String? get() = null

    /** Actions that may change the screen, as opposed to reading it or ending the run. */
    sealed interface ScreenChanging : AgentAction

    data class OpenApp(val name: String, override val sensitive: Boolean, override val reason: String?) : ScreenChanging

    data class Tap(
        val target: ElementTarget,
        val element: ScreenElement?,
        override val sensitive: Boolean,
        override val reason: String?,
    ) : ScreenChanging

    data class EnterText(
        val text: String,
        val target: ElementTarget?,
        val element: ScreenElement?,
        val submit: Boolean,
        override val sensitive: Boolean,
        override val reason: String?,
    ) : ScreenChanging

    data class Scroll(
        val direction: ScrollDirection,
        val target: ElementTarget?,
        override val sensitive: Boolean,
        override val reason: String?,
    ) : ScreenChanging

    data class GoBack(override val sensitive: Boolean, override val reason: String?) : ScreenChanging
    data class GoHome(override val sensitive: Boolean, override val reason: String?) : ScreenChanging
    data object ReadScreen : AgentAction
    data object TakeScreenshot : AgentAction
    data class Finish(val reply: String) : AgentAction
    data class AskUser(val question: String) : AgentAction
}

internal sealed interface ToolValidation {
    data class Valid(val action: AgentAction) : ToolValidation
    data class Invalid(val error: String) : ToolValidation
}

/** The agent's tool definitions and the validation every model tool call goes through. */
internal object AgentTools {
    const val OPEN_APP = "open_app"
    const val READ_SCREEN = "read_screen"
    const val TAP = "tap"
    const val ENTER_TEXT = "enter_text"
    const val SCROLL = "scroll"
    const val GO_BACK = "go_back"
    const val GO_HOME = "go_home"
    const val TAKE_SCREENSHOT = "take_screenshot"
    const val FINISH = "finish"
    const val ASK_USER = "ask_user"

    // Kept terse: the system prompt explains when to set sensitive, and every tool repeats these.
    private const val SAFETY_PROPS = """"sensitive":{"type":"boolean"},"reason":{"type":"string"}"""
    private const val ELEMENT_ID = """"element_id":{"type":"integer"}"""

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            OPEN_APP,
            "Open an installed app by name. Use this instead of looking for app icons.",
            schema(""""name":{"type":"string"},$SAFETY_PROPS""", "name"),
        ),
        ToolSpec(READ_SCREEN, "Read the current screen again, e.g. after content finished loading.", schema("")),
        ToolSpec(
            TAP,
            "Tap an element on the latest screen. Give element_id (preferred) or its visible label.",
            schema("""$ELEMENT_ID,"label":{"type":"string"},$SAFETY_PROPS"""),
        ),
        ToolSpec(
            ENTER_TEXT,
            "Replace the text of an input field (the element_id, or the focused field). submit=true presses Enter/Search afterwards.",
            schema(""""text":{"type":"string"},$ELEMENT_ID,"submit":{"type":"boolean"},$SAFETY_PROPS""", "text"),
        ),
        ToolSpec(
            SCROLL,
            "Scroll the screen, or the given scrollable element, to reveal more content.",
            schema(""""direction":{"type":"string","enum":["up","down","left","right"]},$ELEMENT_ID,$SAFETY_PROPS""", "direction"),
        ),
        ToolSpec(GO_BACK, "Press the Android Back button.", schema(SAFETY_PROPS)),
        ToolSpec(GO_HOME, "Go to the home screen.", schema(SAFETY_PROPS)),
        ToolSpec(
            TAKE_SCREENSHOT,
            "Look at a screenshot. Only when the element list lacks what you need (unlabelled icons, images, web content).",
            schema(""),
        ),
        ToolSpec(
            FINISH,
            "End the task with a short spoken reply (at most 2 short sentences) in the reply language.",
            schema(""""reply":{"type":"string"}""", "reply"),
        ),
        ToolSpec(
            ASK_USER,
            "Ask the user a short question when the request is ambiguous or needs information only they have.",
            schema(""""question":{"type":"string"}""", "question"),
        ),
    )

    /** Tools offered for each kind of screen access; without the screen only launching and replying remain. */
    fun allowed(access: ScreenAccess): Set<String> = when (access) {
        ScreenAccess.Available -> specs.mapTo(LinkedHashSet()) { it.name }
        ScreenAccess.Unavailable -> linkedSetOf(OPEN_APP, FINISH, ASK_USER)
        ScreenAccess.Locked -> linkedSetOf(FINISH, ASK_USER)
    }

    fun specsFor(access: ScreenAccess): List<ToolSpec> = allowed(access).let { names -> specs.filter { it.name in names } }

    /**
     * Checks that [call] names an allowed tool, has well-formed arguments with the required fields,
     * and only refers to elements present in [observation] (the latest screen).
     */
    fun validate(call: ToolCall, allowed: Set<String>, observation: ScreenObservation?): ToolValidation {
        if (specs.none { it.name == call.name }) return invalid("Unknown tool \"${call.name}\". Use one of: ${allowed.joinToString()}.")
        if (call.name !in allowed) return invalid("\"${call.name}\" is not available now. Use one of: ${allowed.joinToString()}.")
        val args = parseArguments(call.argumentsJson) ?: return invalid("The arguments must be a JSON object.")
        return try {
            ToolValidation.Valid(action(call.name, Arguments(args), observation))
        } catch (e: InvalidArgument) {
            invalid(e.message.orEmpty())
        }
    }

    private fun action(tool: String, args: Arguments, observation: ScreenObservation?): AgentAction {
        val sensitive = args.boolean("sensitive") ?: false
        val reason = args.string("reason")?.trim()?.ifEmpty { null }
        return when (tool) {
            OPEN_APP -> AgentAction.OpenApp(args.requiredText("name"), sensitive, reason)
            READ_SCREEN -> AgentAction.ReadScreen
            TAKE_SCREENSHOT -> AgentAction.TakeScreenshot
            TAP -> {
                val id = args.int("element_id")
                val label = args.string("label")?.trim()?.ifEmpty { null }
                when {
                    id != null -> AgentAction.Tap(ElementTarget(id = id), element(observation, id), sensitive, reason)
                    label != null -> {
                        val element = findByLabel(observation, label)
                            ?: throw InvalidArgument("No element labelled \"$label\" on the current screen. Use an element_id from the latest screen.")
                        AgentAction.Tap(ElementTarget(id = element.id, label = label), element, sensitive, reason)
                    }
                    else -> throw InvalidArgument("tap needs element_id or label.")
                }
            }
            ENTER_TEXT -> {
                val text = args.string("text") ?: throw InvalidArgument("\"text\" is required.")
                val element = args.int("element_id")?.let { element(observation, it) }
                AgentAction.EnterText(text, element?.let { ElementTarget(id = it.id) }, element, args.boolean("submit") ?: false, sensitive, reason)
            }
            SCROLL -> {
                val raw = args.requiredText("direction")
                val direction = ScrollDirection.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                    ?: throw InvalidArgument("direction must be up, down, left or right.")
                val target = args.int("element_id")?.let { ElementTarget(id = element(observation, it).id) }
                AgentAction.Scroll(direction, target, sensitive, reason)
            }
            GO_BACK -> AgentAction.GoBack(sensitive, reason)
            GO_HOME -> AgentAction.GoHome(sensitive, reason)
            FINISH -> AgentAction.Finish(args.requiredText("reply").trim())
            ASK_USER -> AgentAction.AskUser(args.requiredText("question").trim())
            else -> throw InvalidArgument("Unknown tool \"$tool\".")
        }
    }

    private fun element(observation: ScreenObservation?, id: Int): ScreenElement =
        observation?.elements?.firstOrNull { it.id == id }
            ?: throw InvalidArgument("Element [$id] is not on the current screen. Use an id from the latest screen.")

    /** Exact (case-insensitive) label first, then the first element whose label contains it. */
    private fun findByLabel(observation: ScreenObservation?, label: String): ScreenElement? {
        val elements = observation?.elements ?: return null
        return elements.firstOrNull { it.labels().any { l -> l.equals(label, ignoreCase = true) } }
            ?: elements.firstOrNull { it.labels().any { l -> l.contains(label, ignoreCase = true) } }
    }

    private fun parseArguments(json: String): JSONObject? {
        if (json.isBlank()) return JSONObject()
        return try {
            JSONObject(json)
        } catch (e: JSONException) {
            null
        }
    }

    private fun schema(properties: String, vararg required: String): String = buildString {
        append("""{"type":"object","properties":{""").append(properties).append('}')
        if (required.isNotEmpty()) append(""","required":[""").append(required.joinToString(",") { "\"$it\"" }).append(']')
        append('}')
    }

    private fun invalid(message: String) = ToolValidation.Invalid(message)

    private class InvalidArgument(message: String) : Exception(message)

    /** Lenient typed reads: models sometimes send numbers as strings or booleans as "true". */
    private class Arguments(private val json: JSONObject) {
        fun string(key: String): String? = when (val value = json.opt(key)) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> value.toString()
        }

        fun requiredText(key: String): String =
            string(key)?.takeIf { it.isNotBlank() } ?: throw InvalidArgument("\"$key\" is required.")

        fun int(key: String): Int? = when (val value = json.opt(key)) {
            null, JSONObject.NULL -> null
            is Number -> value.toDouble().takeIf { it == Math.floor(it) }?.toInt()
                ?: throw InvalidArgument("\"$key\" must be a whole number.")
            is String -> value.trim().trim('[', ']').ifEmpty { null }?.let {
                it.toIntOrNull() ?: throw InvalidArgument("\"$key\" must be a whole number.")
            }
            else -> throw InvalidArgument("\"$key\" must be a whole number.")
        }

        fun boolean(key: String): Boolean? = when (val value = json.opt(key)) {
            is Boolean -> value
            is String -> value.trim().lowercase().toBooleanStrictOrNull()
            else -> null
        }
    }
}

/** Texts a person would use to refer to this element, most specific first. */
internal fun ScreenElement.labels(): List<String> = listOfNotNull(text, description, hint).map { it.trim() }.filter { it.isNotEmpty() }

/** The element's best human-readable name. */
internal fun ScreenElement.label(): String? = labels().firstOrNull()
