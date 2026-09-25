package ai.wakey.android.agent

import ai.wakey.android.BuildConfig
import ai.wakey.android.accessibility.ScreenController
import ai.wakey.android.accessibility.ScreenObservation
import ai.wakey.android.accessibility.ScreenshotResult
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AgentActionInfo
import ai.wakey.android.llm.ChatMessage
import ai.wakey.android.llm.ChatModel
import ai.wakey.android.llm.ChatRequest
import ai.wakey.android.llm.ChatResponse
import ai.wakey.android.llm.DecisionModel
import ai.wakey.android.llm.LlmException
import ai.wakey.android.llm.ToolCall
import ai.wakey.android.tasks.TaskParser
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.UUID

/** What the agent needs from [DeviceActions], as an interface so the loop can be tested off-device. */
internal interface AppLauncher {
    suspend fun open(name: String): AppLaunch
    suspend fun openLink(link: AppLink): AppLaunch
    suspend fun labels(): List<String>
    suspend fun setTorch(on: Boolean): ActionOutcome
}

/**
 * Bounded observe → decide → act → verify loop over the Accessibility UI tree, with a screenshot
 * fallback, plus scheduling work for later. Cancellation of the calling coroutine stops it before
 * the next action. Progress is reported through [AgentListener.onAction]; the UI and the on-screen
 * status pill are drawn from that.
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
    private val decisions: () -> DecisionModel? = { null },
    /** Wall-clock time, for the prompt and for scheduling. */
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
) {
    constructor(
        model: ChatModel,
        device: DeviceActions,
        screen: () -> ScreenController?,
        settings: () -> WakeySettings,
        decisions: () -> DecisionModel? = { null },
    ) : this(
        model = model,
        apps = object : AppLauncher {
            override suspend fun open(name: String) = device.startApp(name)
            override suspend fun openLink(link: AppLink) = device.openLink(link)
            override suspend fun labels() = withContext(Dispatchers.IO) { device.installedAppLabels() }
            override suspend fun setTorch(on: Boolean) = device.setTorch(on)
        },
        screen = screen,
        settings = settings,
        clock = SystemClock::elapsedRealtime,
        timeoutMs = TIMEOUT_MS,
        decisions = decisions,
    )

    /** One id per app process, so every agent call can hit the provider's cached prompt prefix. */
    private val affinityId = "wakey-" + UUID.randomUUID()

    /**
     * Works towards [goal] until the model finishes or asks, or a step, time or safety limit stops it.
     * A [deferred] goal was queued or scheduled earlier and is due now; it can't be scheduled again.
     * A run started before the user surely finished speaking passes [goAhead]: it reads the screen
     * and asks the model, but runs no tool until [goAhead] returns, so dropping it changes nothing.
     */
    suspend fun run(
        goal: String,
        listener: AgentListener,
        deferred: Boolean = false,
        goAhead: (suspend () -> Unit)? = null,
    ): AgentResult {
        val maxSteps = settings().maxAgentSteps.coerceIn(1, WakeySettings.MAX_AGENT_STEPS_LIMIT)
        val session = Session(goal.trim(), listener, maxSteps, deferred, goAhead)
        return withTimeoutOrNull(timeoutMs) { session.run() }
            ?: session.result(AgentStatus.Timeout, session.replies.timeout())
    }

    private inner class Session(
        val goal: String,
        val listener: AgentListener,
        val maxSteps: Int,
        val deferred: Boolean,
        private var goAhead: (suspend () -> Unit)?,
    ) {
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
        /** Size of the screenshot currently in the conversation; tap_point coordinates refer to it. */
        private var shotSize: Pair<Int, Int>? = null
        /** A screenshot to attach once every tool result of the current turn has been added. */
        private var pendingShot: ScreenshotResult? = null
        private var autoShotSignature: String? = null
        private var lastOutcome: ActionOutcome? = null
        private var decisionCalls = 0
        private val timeline = mutableListOf<StepTiming>()
        /** Who chose the step about to run and how long that took; consumed by the first action after it. */
        private var decider = Decider.Llm
        private var thinkMs = 0L
        /** Model turns to leave to the LLM after a fast decision didn't help; the fast model is off for good on errors. */
        private var fastCooldown = 0
        private var fastOff = false
        private var fastCalls = 0
        /** Short descriptions of what has been done, given to the fast model as context. */
        private val actionLog = mutableListOf<String>()
        private var invalidStreak = 0
        private var unchangedStreak = 0
        /** The premature-finish correction is sent at most once per run. */
        private var finishChecked = false

        fun result(status: AgentStatus, reply: String) =
            AgentResult(reply, status, steps, llmCalls, promptTokens, completionTokens, firstActionAt, decisionCalls, timeline.toList())

        /** Adds a step to the timeline, charging it the pending think time. */
        private fun record(action: String, actMs: Long, success: Boolean) {
            if (timeline.size < MAX_TIMELINE) timeline += StepTiming(decider, thinkMs, actMs, action, success)
            thinkMs = 0
        }

        suspend fun run(): AgentResult {
            val controller = screen()
            access = when {
                controller == null -> ScreenAccess.Unavailable
                controller.isLocked -> ScreenAccess.Locked
                else -> ScreenAccess.Available
            }
            val allowed = AgentTools.allowed(access, canSchedule = !deferred)
            val tools = AgentTools.specsFor(access, canSchedule = !deferred)

            messages += ChatMessage.System(AgentPrompt.system(access, apps.labels(), canSchedule = !deferred))
            listener.history().takeLast(MAX_HISTORY).forEach { (role, text) ->
                val clipped = text.take(MAX_HISTORY_CHARS)
                messages += if (role == "user") ChatMessage.User(clipped) else ChatMessage.Assistant(clipped)
            }
            // The request gets its own message so everything up to it stays a stable, cacheable prefix.
            messages += ChatMessage.User(AgentPrompt.request(goal, deferred, now()))
            observation = controller?.takeIf { access == ScreenAccess.Available }?.let { read(it) }
            observation?.let { first ->
                addFullScreen(ChatMessage.User("Current screen:\n${AgentPrompt.screen(first)}"), ChatMessage.User(EARLIER_SCREEN))
            }
            // "YouTube pe lofi chalao", "turn on Bluetooth": the screen the request needs is one intent
            // away, so open it without a model round trip; a search is often complete right there.
            val shortcut = Shortcuts.forGoal(goal)?.takeIf { access != ScreenAccess.Locked }
            if (shortcut != null) {
                val args = JSONObject().put("name", shortcut.link.label).put("link", shortcut.link.uri ?: shortcut.link.action)
                val call = ToolCall(OPENING_CALL_ID, AgentTools.OPEN_APP, args.toString())
                messages += ChatMessage.Assistant(null, listOf(call))
                steps++
                decider = Decider.Direct
                act(AgentAction.OpenLink(shortcut.link), call)?.let { return it }
                shortcut.done?.takeIf { arrived(shortcut) }?.let { done ->
                    record("Finish", 0, true)
                    return result(AgentStatus.Completed, reply(done))
                }
            } else {
                // "Open Instagram and search cats": the first step is known, so run it without a model round trip.
                OpeningStep.appToOpen(goal)?.takeIf { access == ScreenAccess.Available }?.let { app ->
                    val call = ToolCall(OPENING_CALL_ID, AgentTools.OPEN_APP, JSONObject().put("name", app).toString())
                    messages += ChatMessage.Assistant(null, listOf(call))
                    steps++
                    decider = Decider.Direct
                    act(AgentAction.OpenApp(app, sensitive = false, reason = null), call)?.let { return it }
                }
            }

            while (steps < maxSteps) {
                currentCoroutineContext().ensureActive()
                attachScreenshotIfThin()
                val request = ChatRequest(messages.toList(), tools, maxTokens = MAX_COMPLETION_TOKENS, sessionId = affinityId)
                val thinkStart = clock()
                val race = decideNext(request)
                thinkMs = clock() - thinkStart
                decider = if (race is Race.Fast) Decider.Jev else Decider.Llm
                val response = when (race) {
                    is Race.Fast -> {
                        when (val fast = runFastMove(race.move, race.screen)) {
                            is FastResult.Finished -> return fast.result
                            FastResult.Acted, FastResult.Declined -> continue
                        }
                    }
                    is Race.Llm -> race.response.getOrElse { e ->
                        if (e is LlmException) return result(AgentStatus.Failed, replies.llmError(e.kind)) else throw e
                    }
                }
                if (fastCooldown > 0) fastCooldown--
                llmCalls++
                promptTokens += response.promptTokens
                completionTokens += response.completionTokens

                val calls = response.toolCalls.take(MAX_CALLS_PER_TURN)
                if (calls.isEmpty()) {
                    steps++
                    // A plain text answer is a finish.
                    response.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        record("Reply", 0, true)
                        return result(AgentStatus.Completed, it)
                    }
                    messages += ChatMessage.User("Respond with a tool call.")
                    if (++invalidStreak >= MAX_INVALID) return result(AgentStatus.Failed, replies.confused())
                    continue
                }
                // Every kept call needs a tool result, including the ones skipped below.
                messages += ChatMessage.Assistant(response.text?.takeIf { it.isNotBlank() }, calls)
                runTurn(calls, allowed)?.let { return it }
                pendingShot?.let(::attachScreenshot)
                pendingShot = null
            }
            return result(AgentStatus.StepLimit, replies.stepLimit(maxSteps))
        }

        /**
         * Runs the calls of one model turn in order. Later calls were planned without seeing the screen
         * the earlier ones produce, so they run only while everything succeeds, are re-targeted by
         * label, and may only change the screen; the rest are answered as skipped.
         */
        private suspend fun runTurn(calls: List<ToolCall>, allowed: Set<String>): AgentResult? {
            val turnStart = observation
            var skipReason: String? = null
            for ((index, call) in calls.withIndex()) {
                if (skipReason == null && steps >= maxSteps) skipReason = "the step limit was reached"
                if (skipReason != null) {
                    messages += ChatMessage.Tool(call.id, call.name, "SKIPPED: $skipReason. Decide again from the latest screen.")
                    continue
                }
                steps++
                val later = index > 0
                val allowedNow = if (shotSize != null && !later) allowed else allowed - AgentTools.TAP_POINT
                val validation = AgentTools.validate(call, allowedNow, observation, idScreen = turnStart.takeIf { later })
                if (validation is ToolValidation.Invalid) {
                    messages += ChatMessage.Tool(call.id, call.name, "Error: ${validation.error}")
                    if (!later && ++invalidStreak >= MAX_INVALID) return result(AgentStatus.Failed, replies.confused())
                    skipReason = "an earlier call in this turn was rejected"
                    continue
                }
                validation as ToolValidation.Valid
                if (!later) invalidStreak = 0
                val action = validation.action
                if (later && action !is AgentAction.ScreenChanging) {
                    messages += ChatMessage.Tool(
                        call.id, call.name,
                        "SKIPPED: ${call.name} must wait for the new screen. To end with an action, give it done_reply and done_if_visible.",
                    )
                    skipReason = "an earlier call in this turn had to wait for the new screen"
                    continue
                }
                perform(action, call)?.let { return it }
                if (action !is AgentAction.ScreenChanging) {
                    skipReason = "${call.name} came first; its result is needed before acting"
                    continue
                }
                if (lastOutcome?.success != true) {
                    skipReason = "the previous action failed"
                    continue
                }
                validation.done?.let { claim ->
                    if (confirmsDone(claim)) return result(AgentStatus.Completed, claim.reply)
                    noteUnconfirmed(claim)
                    skipReason = "the task isn't confirmed done yet"
                }
            }
            return null
        }

        /**
         * Picks the next step. The LLM request always starts; when the element list alone can decide
         * the step, the fast decision model (Jev, ~0.4 s) races it. A confident Jev answer that arrives
         * first cancels the LLM call; otherwise the LLM's answer is used, so the hybrid is never slower
         * than the LLM alone.
         */
        private suspend fun decideNext(request: ChatRequest): Race = coroutineScope {
            val llmCall = async { attempt { model.complete(request) } }
            val fast = fastCandidate() ?: return@coroutineScope Race.Llm(llmCall.await())
            val (plan, decider, screen) = fast
            val fastCall = async { attempt { decider.choose(plan.state, FastDecision.INSTRUCTIONS, plan.options) } }
            select<Race> {
                llmCall.onAwait { answer ->
                    fastCall.cancel()
                    Race.Llm(answer)
                }
                fastCall.onAwait { decision ->
                    // Unreachable, rejected key, bad reply: the LLM handles the rest of this run.
                    if (decision.isFailure) fastOff = true
                    val move = decision.getOrNull()?.let { FastDecision.interpret(plan, it) }
                    if (move != null) {
                        llmCall.cancel()
                        Race.Fast(move, screen)
                    } else {
                        // Unsure here says nothing about the next screen, so Jev tries again next step.
                        Race.Llm(llmCall.await())
                    }
                }
            }
        }

        /** A decision request for the current screen, when the fast model may decide this step. */
        private fun fastCandidate(): Triple<FastDecision.Plan, DecisionModel, ScreenObservation>? {
            if (fastOff || fastCooldown > 0 || access != ScreenAccess.Available || shotSize != null) return null
            if (fastCalls >= MAX_FAST_CALLS || !FastDecision.suitsGoal(goal)) return null
            val screen = observation?.takeIf { !looksThin(it) && it.packageName != BuildConfig.APPLICATION_ID } ?: return null
            val decider = decisions() ?: return null
            fastCalls++
            return Triple(FastDecision.plan(goal, screen, actionLog, "fast_$fastCalls"), decider, screen)
        }

        /**
         * Runs a confident fast decision through the same safety check and verification as LLM
         * actions. "Done" needs the screen to show the goal, never just a tap.
         */
        private suspend fun runFastMove(move: FastDecision.Move, screen: ScreenObservation): FastResult {
            decisionCalls++
            return when (move) {
                FastDecision.Move.Done -> {
                    if (FinishCheck.unopenedTarget(goal, screen) != null) {
                        fastCooldown = 1
                        FastResult.Declined
                    } else {
                        FastResult.Finished(result(AgentStatus.Completed, replies.done(FastDecision.searchQuery(goal), heading(screen))))
                    }
                }
                is FastDecision.Move.Act -> {
                    steps++
                    messages += ChatMessage.Assistant(null, listOf(move.call))
                    act(move.action, move.call)?.let { return FastResult.Finished(it) }
                    // Same evidence rule as an LLM done claim: e.g. the search words showing in results.
                    move.confirmText?.let { text ->
                        val claim = DoneClaim(replies.done(FastDecision.searchQuery(goal), null), text)
                        if (lastOutcome?.success == true && confirmsDone(claim)) {
                            return FastResult.Finished(result(AgentStatus.Completed, claim.reply))
                        }
                    }
                    // A failed or no-op step: let the LLM look at it before trusting the fast model again.
                    if (lastOutcome?.success != true || unchangedStreak > 0) fastCooldown = 1
                    FastResult.Acted
                }
            }
        }

        /**
         * The shortcut's screen is up: its app is in front (when known) and the expected words show.
         * Web pages load after the browser appears, so one re-read is allowed. Without the screen
         * (screen control off) a launch that worked has to be trusted.
         */
        private suspend fun arrived(shortcut: Shortcut): Boolean {
            if (lastOutcome?.success != true) return false
            if (access != ScreenAccess.Available) return observation == null
            repeat(LINK_READS) { attempt ->
                val screen = observation ?: return false
                val inFront = shortcut.link.packageName == null || screen.packageName == shortcut.link.packageName
                val shown = shortcut.visible.isEmpty() || screen.elements.any { element ->
                    !element.editable && element.labels().any { label -> shortcut.visible.any { label.contains(it, ignoreCase = true) } }
                }
                if (inFront && shown) return true
                if (attempt < LINK_READS - 1) {
                    delay(LINK_RETRY_MS)
                    val controller = screen() ?: return false
                    val next = settledRead(controller) ?: return false
                    observation = next
                    if (next.signature != screen.signature) replaceScreen(next)
                }
            }
            return false
        }

        /** The screen went on changing after the link's tool result was written: keep the model's copy current. */
        private fun replaceScreen(screen: ScreenObservation) {
            if (fullScreenAt != messages.lastIndex) return
            val last = messages[fullScreenAt] as? ChatMessage.Tool ?: return
            val line = last.content.substringBefore("\n\n")
            messages[fullScreenAt] = ChatMessage.Tool(last.toolCallId, last.name, "$line\n\nNew screen:\n${AgentPrompt.screen(screen)}")
        }

        private fun reply(done: ShortcutDone): String = when (done) {
            is ShortcutDone.Results -> replies.done(done.query, null)
            is ShortcutDone.Page -> replies.pageOpen(done.title)
            is ShortcutDone.Navigation -> replies.navigating(done.place)
        }

        /** The screen's title: its first plain-text element, else the app name. */
        private fun heading(screen: ScreenObservation): String? =
            screen.elements.firstOrNull { !it.clickable && !it.editable && it.label() != null }?.label()?.substringBefore(" – ")
                ?: screen.appLabel

        /**
         * An action's done claim holds when the screen changed, the promised text is visible outside
         * input fields, and nothing requested is merely listed. Never on the tap alone.
         */
        private fun confirmsDone(claim: DoneClaim): Boolean {
            val screen = observation ?: return false
            val wanted = claim.visibleText.trim().takeIf { it.length >= MIN_DONE_TEXT } ?: return false
            if (unchangedStreak > 0) return false
            val visible = screen.elements.any { element ->
                !element.editable && element.labels().any { it.contains(wanted, ignoreCase = true) }
            }
            return visible && FinishCheck.unopenedTarget(goal, screen) == null
        }

        private fun noteUnconfirmed(claim: DoneClaim) {
            val last = messages.lastOrNull() as? ChatMessage.Tool ?: return
            messages[messages.lastIndex] = last.copy(
                content = last.content + "\n(Not finished yet: “${claim.visibleText}” isn't visible on this screen.)",
            )
        }

        /**
         * Some apps (games, canvases, custom views) expose almost nothing to accessibility. Attaching a
         * screenshot up front saves the model a take_screenshot round trip. Once per distinct screen.
         */
        private suspend fun attachScreenshotIfThin() {
            val screen = observation ?: return
            if (access != ScreenAccess.Available || shotSize != null || screen.signature == autoShotSignature) return
            if (screen.packageName == BuildConfig.APPLICATION_ID || !looksThin(screen)) return
            autoShotSignature = screen.signature
            val shot = screen()?.screenshot() ?: return
            if (shot.jpegBase64 != null) attachScreenshot(shot, automatic = true)
        }

        private fun attachScreenshot(shot: ScreenshotResult, automatic: Boolean = false) {
            val image = shot.jpegBase64 ?: return
            dropScreenshot()
            val why = if (automatic) "The element list looks incomplete, so a screenshot" else "Screenshot"
            messages += ChatMessage.User(
                "$why of the current screen (${shot.width}×${shot.height} px) is attached. " +
                    "For a control that is only visible here, use tap_point with pixel coordinates in this image.",
                image,
            )
            screenshotAt = messages.lastIndex
            shotSize = shot.width to shot.height
        }

        /** Before the first tool runs: a run started early waits here until its request is confirmed. */
        private suspend fun awaitGoAhead() {
            val wait = goAhead ?: return
            goAhead = null
            wait()
        }

        /** Runs one validated action; returns the final result if it ends the run. */
        private suspend fun perform(action: AgentAction, call: ToolCall): AgentResult? {
            awaitGoAhead()
            when (action) {
                is AgentAction.Finish -> {
                    val correction = if (finishChecked) null else FinishCheck.unopenedTarget(goal, observation)
                    finishChecked = true
                    record("Finish", 0, correction == null)
                    if (correction == null) return result(AgentStatus.Completed, action.reply)
                    messages += ChatMessage.Tool(call.id, call.name, correction)
                }
                is AgentAction.AskUser -> return result(AgentStatus.NeedsUser, action.question)
                is AgentAction.Flashlight -> return flashlight(action, call)
                is AgentAction.Schedule -> schedule(action, call)
                AgentAction.ReadScreen -> readScreen(call)
                AgentAction.TakeScreenshot -> takeScreenshot(call)
                is AgentAction.ScreenChanging -> return act(action, call)
            }
            return null
        }

        /** The torch is a direct device call; when it works, its message is the reply and the run is over. */
        private suspend fun flashlight(action: AgentAction.Flashlight, call: ToolCall): AgentResult? {
            val actStart = clock()
            val info = started(call, if (action.on) "Turning the flashlight on" else "Turning the flashlight off")
            if (firstActionAt == null) firstActionAt = actStart
            val outcome = apps.setTorch(action.on)
            record(info.description, clock() - actStart, outcome.success)
            listener.onAction(info.copy(result = outcome.message, success = outcome.success))
            if (outcome.success) return result(AgentStatus.Completed, outcome.message)
            messages += ChatMessage.Tool(call.id, call.name, outcomeLine(outcome))
            return null
        }

        private fun schedule(action: AgentAction.Schedule, call: ToolCall) {
            val name = action.task.ifBlank { action.kind.label }
            val info = started(call, "Scheduling “${name.take(40)}${if (name.length > 40) "…" else ""}”")
            val request = TaskParser.scheduleAt(action.task, action.whenText, action.kind, now())
            val outcome = if (request == null) {
                ActionOutcome(false, "“${action.whenText}” isn't a future time. Use e.g. \"at 4:30 pm\", \"tomorrow at 9 am\" or \"in 20 minutes\".")
            } else {
                ActionOutcome(true, listener.schedule(request))
            }
            listener.onAction(info.copy(result = outcome.message, success = outcome.success))
            messages += ChatMessage.Tool(call.id, call.name, outcomeLine(outcome))
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
            // Tool messages can't carry images, so the screenshot follows as a user message after the turn.
            if (image != null) pendingShot = shot
        }

        private suspend fun act(action: AgentAction.ScreenChanging, call: ToolCall): AgentResult? {
            awaitGoAhead()
            lastOutcome = null
            SafetyPolicy.review(action, observation?.appLabel, goal)?.let { request ->
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
            val actStart = clock()
            val info = started(call, describe(action))
            if (firstActionAt == null) firstActionAt = actStart
            val before = observation?.signature
            val beforePackage = observation?.packageName
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
                when {
                    launched != null -> it.awaitForeground(launched)
                    // A link any app may answer (a web search): wait for whichever one comes up.
                    action is AgentAction.OpenLink && launch.outcome.success -> it.awaitForegroundChange(beforePackage)
                }
                settledRead(it)
            }
            observation = after
            lastOutcome = launch.outcome
            actionLog += (if (launch.outcome.success) "" else "FAILED: ") + describe(action)
            dropScreenshot()
            record(describe(action), clock() - actStart, launch.outcome.success)
            listener.onAction(info.copy(result = launch.outcome.message, success = launch.outcome.success))
            addToolResult(call, launch.outcome, after, "New screen")

            if (after != null) {
                // scroll_to may find its target without moving, which is progress, not a stuck screen.
                val moved = after.signature != before || (action is AgentAction.ScrollTo && launch.outcome.success)
                unchangedStreak = if (moved) 0 else unchangedStreak + 1
                if (unchangedStreak >= MAX_UNCHANGED) return result(AgentStatus.Failed, replies.stuck())
            }
            return null
        }

        private suspend fun execute(action: AgentAction.ScreenChanging, controller: ScreenController?): AppLaunch =
            when (action) {
                is AgentAction.OpenApp -> apps.open(action.name)
                is AgentAction.OpenLink -> apps.openLink(action.link)
                is AgentAction.Tap -> onScreen(controller) { tap(action.target) }
                is AgentAction.EnterText -> onScreen(controller) { enterText(action.target, action.text, action.submit) }
                is AgentAction.Scroll -> onScreen(controller) { scroll(action.direction, action.target) }
                is AgentAction.ScrollTo -> onScreen(controller) { scrollTo(this, action) }
                is AgentAction.TapPoint -> {
                    val size = shotSize
                    if (size == null) {
                        AppLaunch(ActionOutcome(false, "No screenshot to tap on; call take_screenshot first."))
                    } else {
                        onScreen(controller) { tapPoint(action.x, action.y, size.first, size.second) }
                    }
                }
                is AgentAction.GoBack -> onScreen(controller) { back() }
                is AgentAction.GoHome -> onScreen(controller) { home() }
            }

        private inline fun onScreen(controller: ScreenController?, block: ScreenController.() -> ActionOutcome) =
            AppLaunch(controller?.block() ?: ActionOutcome(false, "Screen control is off."))

        private fun started(call: ToolCall, description: String): AgentActionInfo {
            val info = AgentActionInfo(steps, maxSteps, description, call.name)
            listener.onAction(info)
            return info
        }

        /** Scrolls until an element labelled like [ScrollTo.text] is visible, without a model call per scroll. */
        private suspend fun scrollTo(controller: ScreenController, action: AgentAction.ScrollTo): ActionOutcome {
            var current = observation ?: read(controller) ?: return ActionOutcome(false, "The screen can't be read.")
            for (attempt in 0..MAX_SCROLL_TO) {
                current.elements.firstOrNull { element -> element.labels().any { it.contains(action.text, ignoreCase = true) } }
                    ?.let { return ActionOutcome(true, "Found “${it.label()}”") }
                if (attempt == MAX_SCROLL_TO) break
                val scrolled = controller.scroll(action.direction, null)
                if (!scrolled.success) return ActionOutcome(false, "Couldn't scroll further to look for “${action.text}”.")
                val next = settledRead(controller) ?: return ActionOutcome(false, "The screen can't be read.")
                if (next.signature == current.signature) return ActionOutcome(false, "Reached the end without finding “${action.text}”.")
                current = next
                observation = next
            }
            return ActionOutcome(false, "“${action.text}” wasn't found after $MAX_SCROLL_TO scrolls.")
        }

        /**
         * Reads the screen once it has settled. Apps that animate constantly (feeds, video) never go
         * quiet, so after a short wait the screen counts as settled once two reads show the same content.
         */
        private suspend fun settledRead(controller: ScreenController): ScreenObservation? {
            val startedAt = clock()
            controller.awaitSettled(SETTLE_MS)
            var last = read(controller) ?: return null
            // Returned before the cap: the UI went quiet, so this read is final.
            if (clock() - startedAt < SETTLE_MS - SETTLE_SLACK_MS) return last
            repeat(MAX_STABLE_CHECKS) {
                delay(STABLE_POLL_MS)
                val next = read(controller) ?: return last
                if (next.signature == last.signature) return next
                last = next
            }
            return last
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
            shotSize = null
        }
    }

    private fun describe(action: AgentAction.ScreenChanging): String = when (action) {
        is AgentAction.OpenApp -> "Opening ${action.name}"
        is AgentAction.OpenLink -> "Opening ${action.link.target}"
        is AgentAction.Tap -> "Tapping “${action.element?.label() ?: action.target.label ?: "item ${action.target.id}"}”"
        is AgentAction.EnterText -> "Typing “${action.text.take(40)}${if (action.text.length > 40) "…" else ""}”"
        is AgentAction.Scroll -> "Scrolling ${action.direction.name.lowercase()}"
        is AgentAction.ScrollTo -> "Scrolling to find “${action.text.take(40)}”"
        is AgentAction.TapPoint -> "Tapping the screen at (${action.x}, ${action.y})"
        is AgentAction.GoBack -> "Going back"
        is AgentAction.GoHome -> "Going to the home screen"
    }

    private sealed interface Race {
        data class Llm(val response: Result<ChatResponse>) : Race
        data class Fast(val move: FastDecision.Move, val screen: ScreenObservation) : Race
    }

    /** Like runCatching, but cancellation still propagates. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private sealed interface FastResult {
        data object Declined : FastResult
        data object Acted : FastResult
        data class Finished(val result: AgentResult) : FastResult
    }

    private fun outcomeLine(outcome: ActionOutcome) = (if (outcome.success) "OK: " else "FAILED: ") + outcome.message

    private companion object {
        const val TIMEOUT_MS = 120_000L
        const val MAX_INVALID = 3
        const val MAX_UNCHANGED = 3
        const val MAX_HISTORY = 6
        const val MAX_HISTORY_CHARS = 500
        const val OPENING_CALL_ID = "wakey_open_app"
        /** A tool call needs well under this; it also stops a runaway reply from adding latency. */
        const val MAX_COMPLETION_TOKENS = 400
        const val SETTLE_MS = 650L
        const val SETTLE_SLACK_MS = 50L
        const val STABLE_POLL_MS = 150L
        const val MAX_STABLE_CHECKS = 3
        const val MAX_TIMELINE = 30
        /** Batched calls per model turn; enough for "open search, type, submit". */
        const val MAX_CALLS_PER_TURN = 3
        /** Fast decisions per run; beyond this a task isn't routine and the LLM steers. */
        const val MAX_FAST_CALLS = 10
        const val MAX_SCROLL_TO = 8
        /** Reads of a shortcut's screen before giving up on finishing there: web pages load after the browser shows. */
        const val LINK_READS = 2
        const val LINK_RETRY_MS = 900L
        const val MIN_DONE_TEXT = 2
        const val EARLIER_SCREEN = "(The screen at the start; it has changed since.)"
        const val THIN_MIN_CONTROLS = 2
        const val THIN_MIN_LABELS = 3

        /** Few labelled or tappable elements: the tree probably can't identify the next control. */
        fun looksThin(screen: ScreenObservation): Boolean =
            screen.warning != null ||
                screen.elements.count { it.clickable || it.editable } < THIN_MIN_CONTROLS ||
                screen.elements.count { it.label() != null } < THIN_MIN_LABELS
    }
}
