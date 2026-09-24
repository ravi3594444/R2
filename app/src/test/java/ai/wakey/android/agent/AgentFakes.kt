package ai.wakey.android.agent

import ai.wakey.android.accessibility.ElementTarget
import ai.wakey.android.accessibility.ScreenController
import ai.wakey.android.accessibility.ScreenElement
import ai.wakey.android.accessibility.ScreenObservation
import ai.wakey.android.accessibility.ScreenshotResult
import ai.wakey.android.accessibility.ScrollDirection
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AgentActionInfo
import ai.wakey.android.llm.ChatModel
import ai.wakey.android.llm.ChatRequest
import ai.wakey.android.llm.ChatResponse
import ai.wakey.android.llm.ToolCall
import ai.wakey.android.tasks.TaskRequest
import android.content.Intent
import java.time.ZoneId
import java.time.ZonedDateTime

/** One fake app screen. [texts] are non-clickable labels (titles, summaries); the rest are buttons. */
internal data class FakeUi(
    val packageName: String,
    val appLabel: String,
    val items: List<String>,
    val texts: Set<String> = emptySet(),
    val warning: String? = null,
) {
    fun observation(): ScreenObservation {
        val elements = items.mapIndexed { index, label ->
            val clickable = label !in texts
            ScreenElement(
                id = index + 1, role = if (clickable) "button" else "text", text = label, description = null,
                hint = null, viewId = null, clickable = clickable, editable = false, scrollable = false, checked = null,
                enabled = true, focused = false, left = 0, top = index * 100, right = 1080, bottom = index * 100 + 90,
            )
        }
        val text = elements.joinToString("\n") { "[${it.id}] ${it.role} \"${it.text}\"${if (it.clickable) " (tap)" else ""}" }
        return ScreenObservation(packageName, appLabel, elements, false, "$packageName|${items.joinToString("|")}", text, warning)
    }
}

internal fun element(id: Int, text: String?, description: String? = null, hint: String? = null) = ScreenElement(
    id, "button", text, description, hint, null, clickable = true, editable = false, scrollable = false, checked = null,
    enabled = true, focused = false, left = 0, top = 0, right = 10, bottom = 10,
)

internal fun observationOf(vararg elements: ScreenElement, appLabel: String = "Settings") =
    ScreenObservation("com.android.settings", appLabel, elements.toList(), false, "sig", "")

/** A scriptable [ScreenController] that records every action. */
internal class FakeScreen(var ui: FakeUi) : ScreenController {
    val log = mutableListOf<String>()
    var locked = false
    var screenshotResult = ScreenshotResult(null, 0, 0, "Secure window")

    /** Screen reached by tapping an element with this label, from any screen. */
    val transitions = mutableMapOf<String, FakeUi>()
    var onScroll: (ScrollDirection) -> Unit = {}
    var onText: (String) -> Unit = {}
    private var latest: ScreenObservation = ui.observation()

    override val foregroundPackage: String get() = ui.packageName
    override val isLocked: Boolean get() = locked

    override suspend fun observe(maxElements: Int): ScreenObservation = ui.observation().also { latest = it }

    override suspend fun tap(target: ElementTarget): ActionOutcome {
        val tapped = latest.elements.first { it.id == target.id }
        log += "tap ${tapped.text}"
        transitions[tapped.text]?.let { ui = it }
        return ActionOutcome(true, "Tapped “${tapped.text}”.")
    }

    override suspend fun enterText(target: ElementTarget?, text: String, submit: Boolean): ActionOutcome {
        log += "type $text"
        onText(text)
        return ActionOutcome(true, "Entered text.")
    }

    override suspend fun scroll(direction: ScrollDirection, target: ElementTarget?): ActionOutcome {
        log += "scroll ${direction.name.lowercase()}"
        onScroll(direction)
        return ActionOutcome(true, "Scrolled.")
    }

    override fun back(): ActionOutcome = ActionOutcome(true, "Went back.").also { log += "back" }
    override fun home(): ActionOutcome = ActionOutcome(true, "Went home.").also { log += "home" }
    override suspend fun screenshot(): ScreenshotResult = screenshotResult.also { log += "screenshot" }
    override suspend fun awaitSettled(timeoutMs: Long) = Unit
    override fun launch(intent: Intent): Boolean = false
}

/** Opens apps by exact name by switching the [FakeScreen] to their first screen. */
internal class FakeApps(private val screen: FakeScreen?, private val installed: Map<String, FakeUi>) : AppLauncher {
    val opened = mutableListOf<String>()

    override suspend fun open(name: String): AppLaunch {
        opened += name
        val ui = installed.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?: return AppLaunch(ActionOutcome(false, "I couldn't find an app called $name on this phone."))
        screen?.ui = ui
        return AppLaunch(ActionOutcome(true, "Opening ${ui.appLabel}."), ui.packageName, ui.appLabel)
    }

    override suspend fun labels(): List<String> = installed.values.map { it.appLabel }
}

/** Answers the n-th request (0-based) with [respond]; records every request. */
internal class ScriptedModel(private val respond: suspend (index: Int, request: ChatRequest) -> ChatResponse) : ChatModel {
    val requests = mutableListOf<ChatRequest>()

    override suspend fun complete(request: ChatRequest): ChatResponse {
        requests += request
        return respond(requests.size - 1, request)
    }

    override suspend fun testConnection() = "OK"

    companion object {
        fun of(vararg responses: ChatResponse) = ScriptedModel { index, _ ->
            responses.getOrNull(index) ?: error("Unexpected model call #${index + 1}")
        }
    }
}

internal fun toolCall(name: String, args: String = "{}", id: String = "call_${name}_${args.hashCode()}") =
    ChatResponse(null, listOf(ToolCall(id, name, args)), "tool_calls", promptTokens = 100, completionTokens = 10, latencyMs = 5)

internal fun textReply(text: String) = ChatResponse(text, emptyList(), "stop", promptTokens = 100, completionTokens = 10, latencyMs = 5)

internal class RecordingListener(
    var approve: Boolean = true,
    private val history: List<Pair<String, String>> = emptyList(),
) : AgentListener {
    val actions = mutableListOf<AgentActionInfo>()
    val confirmations = mutableListOf<ConfirmationRequest>()
    val scheduled = mutableListOf<TaskRequest.Scheduled>()

    override fun schedule(request: TaskRequest.Scheduled): String {
        scheduled += request
        return "Scheduled ${request.text}."
    }
    var onActionHook: (AgentActionInfo) -> Unit = {}

    override fun onAction(action: AgentActionInfo) {
        actions += action
        onActionHook(action)
    }

    override suspend fun confirm(request: ConfirmationRequest): Boolean {
        confirmations += request
        return approve
    }

    override fun history(): List<Pair<String, String>> = history
}

internal fun agentLoop(
    model: ChatModel,
    screen: FakeScreen?,
    apps: AppLauncher = FakeApps(screen, emptyMap()),
    maxSteps: Int = WakeySettings.DEFAULT_MAX_STEPS,
    clock: () -> Long = { 1_000L },
    timeoutMs: Long = 120_000L,
    now: () -> ZonedDateTime = { FIXED_NOW },
) = AgentLoop(model, apps, { screen }, { WakeySettings(maxAgentSteps = maxSteps) }, clock, timeoutMs, now)

/** Thursday 24 September 2026, 2:00 PM in India. */
internal val FIXED_NOW: ZonedDateTime = ZonedDateTime.of(2026, 9, 24, 14, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
