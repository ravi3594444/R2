package ai.wakey.android.agent

import ai.wakey.android.core.AgentActionInfo
import ai.wakey.android.tasks.TaskRequest

/** A simple command handled directly on Android with no LLM call. */
sealed interface FastCommand {
    data class Torch(val on: Boolean) : FastCommand
    data class OpenApp(val appName: String) : FastCommand
    data object GoHome : FastCommand
    data object GoBack : FastCommand
}

/** Result of one direct Android action. [message] is short and user-facing (it is spoken). */
data class ActionOutcome(val success: Boolean, val message: String)

enum class AgentStatus { Completed, NeedsUser, Failed, Cancelled, StepLimit, Timeout }

data class AgentResult(
    /** Short reply to show and speak. */
    val reply: String,
    val status: AgentStatus,
    val steps: Int,
    val llmCalls: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    /** SystemClock.elapsedRealtime() when the first Android action executed, if any. */
    val firstActionAtMs: Long?,
    /** Steps decided by the fast decision model (Jev) instead of the LLM. */
    val decisionCalls: Int = 0,
    /** Where the time went, step by step, for Diagnostics. */
    val timeline: List<StepTiming> = emptyList(),
)

/** One step of an agent run: who chose it, how long choosing took and how long doing it took. */
data class StepTiming(
    val decidedBy: Decider,
    /** Waiting for the model(s) that chose this step; 0 for later calls of a batched turn. */
    val thinkMs: Long,
    /** Performing the action and waiting for the screen to settle. */
    val actMs: Long,
    val action: String,
    val success: Boolean,
)

enum class Decider(val label: String) { Jev("Jev"), Llm("LLM"), Direct("direct") }

data class ConfirmationRequest(
    /** e.g. "Send this WhatsApp message to Priya?" */
    val question: String,
    /** e.g. the message text or the button that will be tapped. */
    val detail: String,
)

/** Callbacks from the agent loop to the orchestrator/UI. Called on the loop's coroutine. */
interface AgentListener {
    /** A step is starting ([AgentActionInfo.result] null) or has finished. */
    fun onAction(action: AgentActionInfo)

    /** Suspend until the user approves or denies. Must return false if cancelled. */
    suspend fun confirm(request: ConfirmationRequest): Boolean

    /** Previous turns, oldest first, as (role, text) with role "user" or "assistant". */
    fun history(): List<Pair<String, String>> = emptyList()

    /**
     * Schedules [request] (the model called schedule_task) and returns a short outcome for the
     * model, e.g. "Scheduled call mum at 4 PM."
     */
    fun schedule(request: TaskRequest.Scheduled): String = "Scheduling isn't available."
}
