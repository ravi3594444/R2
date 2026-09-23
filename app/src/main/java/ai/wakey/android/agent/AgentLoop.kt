package ai.wakey.android.agent

import ai.wakey.android.WakeyApp
import ai.wakey.android.accessibility.ScreenController
import ai.wakey.android.accessibility.ScreenObservation
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AgentActionInfo
import ai.wakey.android.llm.ChatMessage
import ai.wakey.android.llm.ChatModel
import ai.wakey.android.llm.ChatRequest
import ai.wakey.android.llm.LlmException
import ai.wakey.android.llm.ToolCall
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the agent needs from [DeviceActions], as an interface so the loop can be tested off-device. */
internal interface AppLauncher {
    suspend fun open(name: String): AppLaunch
    suspend fun labels(): List<String>
}

/**
 * Bounded observe → decide → act → verify loop over the Accessibility UI tree, with a screenshot
 * fallback. Cancellation of the calling coroutine stops it before the next action.
 *
 * Each step sends the system prompt, recent conversation, the request and the latest screen to the
 * model, validates the single tool call it returns, asks the user before consequential actions,
 * executes it, waits for the screen to settle and reads it again. Only the latest screen is kept
 * in full; earlier tool results shrink to their outcome line so tokens stay bounded.
 */
class AgentLoop internal constructor(
    private val model: ChatModel,
    private val apps: AppLauncher,
    private val screen: () -> ScreenController?,
    private val settings: () -> WakeySettings,
    private val clock: () -> Long,
    private val timeoutMs: Long,
    private val onStopRequested: () -> Unit,
) {
    constructor(
        model: ChatModel,
        device: DeviceActions,
        screen: () -> ScreenController?,
        settings: () -> WakeySettings,
    ) : this(
        model = model,
        apps = object : AppLauncher {
            override suspend fun open(name: String) = device.startApp(name)
            override suspend fun labels() = withContext(Dispatchers.IO) { device.installedAppLabels() }
        },
        screen = screen,
        settings = settings,
        clock = SystemClock::elapsedRealtime,
        timeoutMs = TIMEOUT_MS,
        onStopRequested = { WakeyApp.graph.controller.stop() },
    )

    /** Works towards [goal] until the model finishes or asks, or a step, time or safety limit stops it. */
    suspend fun run(goal: String, listener: AgentListener): AgentResult {
        val maxSteps = settings().maxAgentSteps.coerceIn(1, WakeySettings.MAX_AGENT_STEPS_LIMIT)
        val session = Session(goal.trim(), listener, maxSteps)
        return try {
            withTimeoutOrNull(timeoutMs) { session.run() }
                ?: session.result(AgentStatus.Timeout, session.replies.timeout())
        } finally {
            screen()?.hideStatus()
        }
    }

    private inner class Session(val goal: String, val listener: AgentListener, val maxSteps: Int) {
        val replies = AgentPrompt.Replies.forGoal(goal)
        private val messages = mutableListOf<ChatMessage>()
        private var access = ScreenAccess.Unavailable
        private var steps = 0
        private var llmCalls = 0
        private var promptTokens = 0
        private var completionTokens = 0
        private var firstActionAt: Long? = null

        /** The latest screen; element ids in tool calls are checked against it. */
        private var observation: ScreenObservation? = null

        /** The one message holding a full screen, and the outcome-only form it shrinks to later. */
        private var fullScreenAt = -1
        private var fullScreenShort: ChatMessage? = null
        private var screenshotAt = -1
        private var invalidStreak = 0
        private var unchangedStreak = 0
        /** The premature-finish correction is sent at most once per run. */
        private var finishChecked = false

        fun result(status: AgentStatus, reply: String) =
            AgentResult(reply, status, steps, llmCalls, promptTokens, completionTokens, firstActionAt)

        suspend fun run(): AgentResult {
            val controller = screen()
            access = when {
                controller == null -> ScreenAccess.Unavailable
                controller.isLocked -> ScreenAccess.Locked
                else -> ScreenAccess.Available
            }
            val allowed = AgentTools.allowed(access)
            val tools = AgentTools.specsFor(access)
            if (access == ScreenAccess.Available) controller?.showStatus(LOOKING_STATUS, onStopRequested)

            messages += ChatMessage.System(AgentPrompt.system(access, apps.labels()))
            listener.history().takeLast(MAX_HISTORY).forEach { (role, text) ->
                val clipped = text.take(MAX_HISTORY_CHARS)
                messages += if (role == "user") ChatMessage.User(clipped) else ChatMessage.Assistant(clipped)
            }
            val request = AgentPrompt.request(goal)
            observation = controller?.takeIf { access == ScreenAccess.Available }?.let { read(it) }
            val first = observation
            if (first != null) {
                addFullScreen(ChatMessage.User("$request\n\nCurrent screen:\n${AgentPrompt.screen(first)}"), ChatMessage.User(request))
            } else {
                messages += ChatMessage.User(request)
            }

            while (steps < maxSteps) {
                currentCoroutineContext().ensureActive()
                steps++
                val response = try {
                    model.complete(ChatRequest(messages.toList(), tools))
                } catch (e: LlmException) {
                    return result(AgentStatus.Failed, replies.llmError(e.kind))
                }
                llmCalls++
                promptTokens += response.promptTokens
                completionTokens += response.completionTokens

                val call = response.toolCalls.firstOrNull()
                if (call == null) {
                    // A plain text answer is a finish.
                    response.text?.trim()?.takeIf { it.isNotEmpty() }?.let { return result(AgentStatus.Completed, it) }
                    messages += ChatMessage.User("Respond with exactly one tool call.")
                    if (++invalidStreak >= MAX_INVALID) return result(AgentStatus.Failed, replies.confused())
                    continue
                }
                // Only the first call runs, so only it is kept: every kept call needs a tool result.
                messages += ChatMessage.Assistant(response.text?.takeIf { it.isNotBlank() }, listOf(call))
                when (val validation = AgentTools.validate(call, allowed, observation)) {
                    is ToolValidation.Invalid -> {
                        messages += ChatMessage.Tool(call.id, call.name, "Error: ${validation.error}")
                        if (++invalidStreak >= MAX_INVALID) return result(AgentStatus.Failed, replies.confused())
                    }
                    is ToolValidation.Valid -> {
                        invalidStreak = 0
                        perform(validation.action, call)?.let { return it }
                    }
                }
            }
            return result(AgentStatus.StepLimit, replies.stepLimit(maxSteps))
        }

        /** Runs one validated action; returns the final result if it ends the run. */
        private suspend fun perform(action: AgentAction, call: ToolCall): AgentResult? {
            when (action) {
                is AgentAction.Finish -> {
                    val correction = if (finishChecked) null else FinishCheck.unopenedTarget(goal, observation)
                    finishChecked = true
                    if (correction == null) return result(AgentStatus.Completed, action.reply)
                    messages += ChatMessage.Tool(call.id, call.name, correction)
                }
                is AgentAction.AskUser -> return result(AgentStatus.NeedsUser, action.question)
                AgentAction.ReadScreen -> readScreen(call)
                AgentAction.TakeScreenshot -> takeScreenshot(call)
                is AgentAction.ScreenChanging -> return act(action, call)
            }
            return null
        }

        private suspend fun readScreen(call: ToolCall) {
            val info = started(call, "Reading the screen")
            val observed = screen()?.let { read(it) }
            observation = observed
            val outcome = if (observed != null) ActionOutcome(true, "Screen read.") else ActionOutcome(false, "The screen can't be read.")
            listener.onAction(info.copy(result = outcome.message, success = outcome.success))
            addToolResult(call, outcome, observed, "Screen")
        }

        private suspend fun takeScreenshot(call: ToolCall) {
            val info = started(call, "Taking a screenshot")
            val shot = screen()?.screenshot()
            val image = shot?.jpegBase64
            val outcome = if (image != null) {
                ActionOutcome(true, "Screenshot attached in the next message.")
            } else {
                ActionOutcome(false, "No screenshot (${shot?.error ?: "screen control is off"}). Secure screens can't be captured.")
            }
            listener.onAction(info.copy(result = outcome.message, success = outcome.success))
            messages += ChatMessage.Tool(call.id, call.name, outcomeLine(outcome))
            if (image != null) {
                // Tool messages can't carry images, so the screenshot follows as a user message.
                dropScreenshot()
                messages += ChatMessage.User("Screenshot of the current screen (${shot.width}×${shot.height}).", image)
                screenshotAt = messages.lastIndex
            }
        }

        private suspend fun act(action: AgentAction.ScreenChanging, call: ToolCall): AgentResult? {
            SafetyPolicy.review(action, observation?.appLabel)?.let { request ->
                val approved = listener.confirm(request)
                currentCoroutineContext().ensureActive()
                if (!approved) {
                    messages += ChatMessage.Tool(
                        call.id, call.name, "NOT DONE: the user declined this action. Don't retry it; finish or ask_user.",
                    )
                    return null
                }
            }
            currentCoroutineContext().ensureActive()
            val info = started(call, describe(action))
            if (firstActionAt == null) firstActionAt = clock()
            val before = observation?.signature
            val controller = screen()
            val launch = try {
                execute(action, controller)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLaunch(ActionOutcome(false, "The action failed (${e.javaClass.simpleName})."))
            }
            val after = controller?.takeIf { !it.isLocked }?.let {
                val launched = launch.packageName
                if (launched != null) it.awaitForeground(launched) else it.awaitSettled()
                read(it)
            }
            observation = after
            dropScreenshot()
            listener.onAction(info.copy(result = launch.outcome.message, success = launch.outcome.success))
            addToolResult(call, launch.outcome, after, "New screen")

            if (after != null) {
                unchangedStreak = if (after.signature == before) unchangedStreak + 1 else 0
                if (unchangedStreak >= MAX_UNCHANGED) return result(AgentStatus.Failed, replies.stuck())
            }
            return null
        }

        private suspend fun execute(action: AgentAction.ScreenChanging, controller: ScreenController?): AppLaunch =
            when (action) {
                is AgentAction.OpenApp -> apps.open(action.name)
                is AgentAction.Tap -> onScreen(controller) { tap(action.target) }
                is AgentAction.EnterText -> onScreen(controller) { enterText(action.target, action.text, action.submit) }
                is AgentAction.Scroll -> onScreen(controller) { scroll(action.direction, action.target) }
                is AgentAction.GoBack -> onScreen(controller) { back() }
                is AgentAction.GoHome -> onScreen(controller) { home() }
            }

        private inline fun onScreen(controller: ScreenController?, block: ScreenController.() -> ActionOutcome) =
            AppLaunch(controller?.block() ?: ActionOutcome(false, "Screen control is off."))

        private fun started(call: ToolCall, description: String): AgentActionInfo {
            val info = AgentActionInfo(steps, maxSteps, description, call.name)
            listener.onAction(info)
            screen()?.showStatus(description, onStopRequested)
            return info
        }

        private suspend fun read(controller: ScreenController): ScreenObservation? {
            if (controller.isLocked) return null
            return try {
                controller.observe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

        private fun addToolResult(call: ToolCall, outcome: ActionOutcome, observed: ScreenObservation?, heading: String) {
            val line = outcomeLine(outcome)
            when {
                observed != null -> addFullScreen(
                    ChatMessage.Tool(call.id, call.name, "$line\n\n$heading:\n${AgentPrompt.screen(observed)}"),
                    ChatMessage.Tool(call.id, call.name, line),
                )
                access == ScreenAccess.Available -> {
                    shrinkScreen()
                    messages += ChatMessage.Tool(call.id, call.name, "$line\n${AgentPrompt.SCREEN_UNREADABLE}")
                }
                else -> messages += ChatMessage.Tool(call.id, call.name, "$line\n(Screen control is off, so the result can't be checked.)")
            }
        }

        /** Appends [full] as the only message with a full screen; [short] replaces it once superseded. */
        private fun addFullScreen(full: ChatMessage, short: ChatMessage) {
            shrinkScreen()
            messages += full
            fullScreenAt = messages.lastIndex
            fullScreenShort = short
        }

        private fun shrinkScreen() {
            fullScreenShort?.let { messages[fullScreenAt] = it }
            fullScreenShort = null
            fullScreenAt = -1
        }

        private fun dropScreenshot() {
            if (screenshotAt >= 0) messages[screenshotAt] = ChatMessage.User("(An earlier screenshot was removed; the screen has changed.)")
            screenshotAt = -1
        }
    }

    private fun describe(action: AgentAction.ScreenChanging): String = when (action) {
        is AgentAction.OpenApp -> "Opening ${action.name}"
        is AgentAction.Tap -> "Tapping “${action.element?.label() ?: action.target.label ?: "item ${action.target.id}"}”"
        is AgentAction.EnterText -> "Typing “${action.text.take(40)}${if (action.text.length > 40) "…" else ""}”"
        is AgentAction.Scroll -> "Scrolling ${action.direction.name.lowercase()}"
        is AgentAction.GoBack -> "Going back"
        is AgentAction.GoHome -> "Going to the home screen"
    }

    private fun outcomeLine(outcome: ActionOutcome) = (if (outcome.success) "OK: " else "FAILED: ") + outcome.message

    private companion object {
        const val TIMEOUT_MS = 120_000L
        const val MAX_INVALID = 3
        const val MAX_UNCHANGED = 3
        const val MAX_HISTORY = 6
        const val MAX_HISTORY_CHARS = 500
        const val LOOKING_STATUS = "Looking at the screen"
    }
}
