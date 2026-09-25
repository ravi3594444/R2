package ai.wakey.android.agent

import ai.wakey.android.accessibility.ElementTarget
import ai.wakey.android.accessibility.ScreenElement
import ai.wakey.android.accessibility.ScreenObservation
import ai.wakey.android.accessibility.ScrollDirection
import ai.wakey.android.llm.ToolCall
import ai.wakey.android.llm.ToolSpec
import ai.wakey.android.tasks.TaskKind
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

    /** A deep link: from [Shortcuts] for common requests, or the model's own open_link call. */
    data class OpenLink(
        val link: AppLink,
        override val sensitive: Boolean = false,
        override val reason: String? = null,
    ) : ScreenChanging

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

    /** Taps pixel ([x], [y]) of the latest screenshot. */
    data class TapPoint(val x: Int, val y: Int, override val sensitive: Boolean, override val reason: String?) : ScreenChanging

    /** Scrolls until an element whose label contains [text] is visible. */
    data class ScrollTo(
        val text: String,
        val direction: ScrollDirection,
        override val sensitive: Boolean,
        override val reason: String?,
    ) : ScreenChanging

    data class GoBack(override val sensitive: Boolean, override val reason: String?) : ScreenChanging
    data class GoHome(override val sensitive: Boolean, override val reason: String?) : ScreenChanging

    /** The torch: a direct device call that works without the screen, even on the lock screen. */
    data class Flashlight(val on: Boolean) : AgentAction
    data object ReadScreen : AgentAction
    data object TakeScreenshot : AgentAction
    data class Finish(val reply: String) : AgentAction
    data class AskUser(val question: String) : AgentAction

    /** Do [task] later, at [whenText] ("at 4 pm", "in 20 minutes"). */
    data class Schedule(val task: String, val whenText: String, val kind: TaskKind) : AgentAction
}

/**
 * The model's claim that an action completes the request: [reply] is spoken if [visibleText] shows
 * up on the next screen, so the run can end without another model call.
 */
internal data class DoneClaim(val reply: String, val visibleText: String)

internal sealed interface ToolValidation {
    data class Valid(val action: AgentAction, val done: DoneClaim? = null) : ToolValidation
    data class Invalid(val error: String) : ToolValidation
}

/** The agent's tool definitions and the validation every model tool call goes through. */
internal object AgentTools {
    const val OPEN_APP = "open_app"
    const val OPEN_LINK = "open_link"
    const val READ_SCREEN = "read_screen"
    const val TAP = "tap"
    const val ENTER_TEXT = "enter_text"
    const val SCROLL = "scroll"
    const val GO_BACK = "go_back"
    const val GO_HOME = "go_home"
    const val SET_FLASHLIGHT = "set_flashlight"
    const val TAKE_SCREENSHOT = "take_screenshot"
    const val TAP_POINT = "tap_point"
    const val SCROLL_TO = "scroll_to"
    const val FINISH = "finish"
    const val ASK_USER = "ask_user"
    const val SCHEDULE_TASK = "schedule_task"

    // Kept terse: the system prompt explains when to set sensitive, and every tool repeats these.
    private const val SAFETY_PROPS = """"sensitive":{"type":"boolean"},"reason":{"type":"string"}"""
    private const val ELEMENT_ID = """"element_id":{"type":"integer"}"""
    private const val DONE_PROPS = """"done_reply":{"type":"string"},"done_if_visible":{"type":"string"}"""
    private const val DIRECTION = """"direction":{"type":"string","enum":["up","down","left","right"]}"""

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            OPEN_APP,
            "Open an installed app by name. Use this instead of looking for app icons.",
            schema(""""name":{"type":"string"},$SAFETY_PROPS""", "name"),
        ),
        ToolSpec(
            OPEN_LINK,
            "Jump straight to a screen by link instead of tapping there: an https URL (YouTube results, a Maps search, an Amazon " +
                "or Instagram page, a wa.me chat), a geo:/market:/spotify:/tel:/mailto:/sms: link, or an Android settings action " +
                "such as android.settings.WIFI_SETTINGS. app: the app it should open in, if known.",
            schema(""""link":{"type":"string"},"app":{"type":"string"},$DONE_PROPS,$SAFETY_PROPS""", "link"),
        ),
        ToolSpec(READ_SCREEN, "Read the current screen again, e.g. after content finished loading.", schema("")),
        ToolSpec(
            TAP,
            "Tap an element on the latest screen. Give element_id (preferred) or its visible label.",
            schema("""$ELEMENT_ID,"label":{"type":"string"},$DONE_PROPS,$SAFETY_PROPS"""),
        ),
        ToolSpec(
            ENTER_TEXT,
            "Type into an input field (element_id, or the focused field). A search icon or fake search box is tapped first automatically. submit=true presses Enter/Search.",
            schema(""""text":{"type":"string"},$ELEMENT_ID,"submit":{"type":"boolean"},$DONE_PROPS,$SAFETY_PROPS""", "text"),
        ),
        ToolSpec(
            SCROLL,
            "Scroll the screen, or the given scrollable element, to reveal more content.",
            schema("""$DIRECTION,$ELEMENT_ID,$SAFETY_PROPS""", "direction"),
        ),
        ToolSpec(
            SCROLL_TO,
            "Scroll (default down) until an element whose label contains text is visible. Use instead of repeated scroll calls.",
            schema(""""text":{"type":"string"},$DIRECTION,$SAFETY_PROPS""", "text"),
        ),
        ToolSpec(
            TAP_POINT,
            "Tap pixel x,y of the latest screenshot. Only for controls missing from the element list.",
            schema(""""x":{"type":"integer"},"y":{"type":"integer"},$DONE_PROPS,$SAFETY_PROPS""", "x", "y"),
        ),
        ToolSpec(GO_BACK, "Press the Android Back button.", schema(SAFETY_PROPS)),
        ToolSpec(GO_HOME, "Go to the home screen.", schema(SAFETY_PROPS)),
        ToolSpec(
            SET_FLASHLIGHT,
            "Turn the phone's flashlight (torch) on or off. The flashlight is not an app and not in Settings; this completes the request.",
            schema(""""on":{"type":"boolean"}""", "on"),
        ),
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
        ToolSpec(
            SCHEDULE_TASK,
            "Do something later instead of now. task: the request to carry out then (for a reminder, what to remind about). " +
                "when: e.g. \"at 4 pm\", \"tomorrow at 9:30 am\", \"in 20 minutes\". kind: task (Wakey does it), reminder or alarm.",
            schema(""""task":{"type":"string"},"when":{"type":"string"},"kind":{"type":"string","enum":["task","reminder","alarm"]}""", "task", "when"),
        ),
    )

    /**
     * Tools offered for each kind of screen access; without the screen only launching, replying and
     * scheduling remain. [canSchedule] is false for tasks that are themselves scheduled runs.
     */
    fun allowed(access: ScreenAccess, canSchedule: Boolean = true): Set<String> {
        val names = when (access) {
            ScreenAccess.Available -> specs.mapTo(LinkedHashSet()) { it.name }
            ScreenAccess.Unavailable -> linkedSetOf(OPEN_APP, OPEN_LINK, SET_FLASHLIGHT, FINISH, ASK_USER, SCHEDULE_TASK)
            ScreenAccess.Locked -> linkedSetOf(SET_FLASHLIGHT, FINISH, ASK_USER, SCHEDULE_TASK)
        }
        if (!canSchedule) names.remove(SCHEDULE_TASK)
        return names
    }

    fun specsFor(access: ScreenAccess, canSchedule: Boolean = true): List<ToolSpec> =
        allowed(access, canSchedule).let { names -> specs.filter { it.name in names } }

    /**
     * Checks that [call] names an allowed tool, has well-formed arguments with the required fields,
     * and only refers to elements present in [observation] (the latest screen).
     */
    fun validate(
        call: ToolCall,
        allowed: Set<String>,
        observation: ScreenObservation?,
        idScreen: ScreenObservation? = null,
    ): ToolValidation {
        if (specs.none { it.name == call.name }) return invalid("Unknown tool \"${call.name}\". Use one of: ${allowed.joinToString()}.")
        if (call.name == TAP_POINT && call.name !in allowed) return invalid("tap_point needs a current screenshot; call take_screenshot first.")
        if (call.name !in allowed) return invalid("\"${call.name}\" is not available now. Use one of: ${allowed.joinToString()}.")
        val args = parseArguments(call.argumentsJson) ?: return invalid("The arguments must be a JSON object.")
        return try {
            val parsed = Arguments(args)
            ToolValidation.Valid(action(call.name, parsed, Screens(observation, idScreen)), doneClaim(parsed))
        } catch (e: InvalidArgument) {
            invalid(e.message.orEmpty())
        }
    }

    private fun doneClaim(args: Arguments): DoneClaim? {
        val reply = args.string("done_reply")?.trim()?.ifEmpty { null } ?: return null
        val visible = args.string("done_if_visible")?.trim()?.ifEmpty { null } ?: return null
        return DoneClaim(reply, visible)
    }

    /**
     * The screen ids are checked against. Later calls in a batched turn were written against the
     * turn's first screen ([idScreen]), so their ids are mapped by label onto the current screen.
     */
    private class Screens(val current: ScreenObservation?, val idScreen: ScreenObservation?) {
        val elements get() = current?.elements

        fun element(id: Int): ScreenElement {
            val source = idScreen?.takeIf { it !== current }
                ?: return current?.elements?.firstOrNull { it.id == id }
                    ?: throw InvalidArgument("Element [$id] is not on the current screen. Use an id from the latest screen.")
            val old = source.elements.firstOrNull { it.id == id }
                ?: throw InvalidArgument("Element [$id] was not on the screen this turn started from.")
            val label = old.label() ?: throw InvalidArgument("Element [$id] has no label to find it again after the screen changed.")
            return findByLabel(current, label)
                ?: throw InvalidArgument("“$label” is not on the new screen; decide the next step from it.")
        }
    }

    private fun action(tool: String, args: Arguments, observation: Screens): AgentAction {
        val sensitive = args.boolean("sensitive") ?: false
        val reason = args.string("reason")?.trim()?.ifEmpty { null }
        return when (tool) {
            OPEN_APP -> AgentAction.OpenApp(args.requiredText("name"), sensitive, reason)
            OPEN_LINK -> {
                val link = try {
                    AppLinks.parse(args.requiredText("link"), args.string("app"))
                } catch (e: IllegalArgumentException) {
                    throw InvalidArgument(e.message.orEmpty())
                }
                AgentAction.OpenLink(link, sensitive, reason)
            }
            READ_SCREEN -> AgentAction.ReadScreen
            TAKE_SCREENSHOT -> AgentAction.TakeScreenshot
            TAP -> {
                val id = args.int("element_id")
                val label = args.string("label")?.trim()?.ifEmpty { null }
                when {
                    id != null -> observation.element(id).let { AgentAction.Tap(ElementTarget(id = it.id), it, sensitive, reason) }
                    label != null -> {
                        val element = findByLabel(observation.current, label)
                            ?: throw InvalidArgument("No element labelled \"$label\" on the current screen. Use an element_id from the latest screen.")
                        AgentAction.Tap(ElementTarget(id = element.id, label = label), element, sensitive, reason)
                    }
                    else -> throw InvalidArgument("tap needs element_id or label.")
                }
            }
            ENTER_TEXT -> {
                val text = args.string("text") ?: throw InvalidArgument("\"text\" is required.")
                val element = args.int("element_id")?.let { observation.element(it) }
                AgentAction.EnterText(text, element?.let { ElementTarget(id = it.id) }, element, args.boolean("submit") ?: false, sensitive, reason)
            }
            SCROLL -> {
                val direction = direction(args.requiredText("direction"))
                val target = args.int("element_id")?.let { ElementTarget(id = observation.element(it).id) }
                AgentAction.Scroll(direction, target, sensitive, reason)
            }
            SCROLL_TO -> AgentAction.ScrollTo(
                args.requiredText("text").trim(),
                args.string("direction")?.let(::direction) ?: ScrollDirection.Down,
                sensitive,
                reason,
            )
            TAP_POINT -> AgentAction.TapPoint(
                args.int("x") ?: throw InvalidArgument("\"x\" is required."),
                args.int("y") ?: throw InvalidArgument("\"y\" is required."),
                sensitive,
                reason,
            )
            GO_BACK -> AgentAction.GoBack(sensitive, reason)
            GO_HOME -> AgentAction.GoHome(sensitive, reason)
            SET_FLASHLIGHT -> AgentAction.Flashlight(args.boolean("on") ?: throw InvalidArgument("\"on\" is required: true or false."))
            FINISH -> AgentAction.Finish(args.requiredText("reply").trim())
            ASK_USER -> AgentAction.AskUser(args.requiredText("question").trim())
            SCHEDULE_TASK -> {
                val kind = when (args.string("kind")?.trim()?.lowercase()) {
                    null, "", "task" -> TaskKind.Task
                    "reminder" -> TaskKind.Reminder
                    "alarm", "timer" -> TaskKind.Alarm
                    else -> throw InvalidArgument("kind must be task, reminder or alarm.")
                }
                val task = args.string("task")?.trim().orEmpty()
                if (task.isEmpty() && kind != TaskKind.Alarm) throw InvalidArgument("\"task\" is required.")
                AgentAction.Schedule(task, args.requiredText("when").trim(), kind)
            }
            else -> throw InvalidArgument("Unknown tool \"$tool\".")
        }
    }

    private fun direction(raw: String): ScrollDirection =
        ScrollDirection.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
            ?: throw InvalidArgument("direction must be up, down, left or right.")

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
