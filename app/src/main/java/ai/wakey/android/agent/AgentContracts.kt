package ai.wakey.android.agent

import ai.wakey.android.core.AgentActionInfo

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
)

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
}
