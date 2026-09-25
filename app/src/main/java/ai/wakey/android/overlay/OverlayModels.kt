package ai.wakey.android.overlay

import ai.wakey.android.core.AgentActionInfo
import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.core.Speaker
import ai.wakey.android.tasks.TaskBoard

/** What the status pill is showing. */
internal enum class PillMode { Listening, Thinking, Acting, Confirm, Done, Failed }

internal data class PillModel(
    val mode: PillMode,
    val text: String,
    /** The agent step [text] belongs to, for multi-step tasks. */
    val step: Int? = null,
    /** The step finished and Wakey is working out the next one. */
    val deciding: Boolean = false,
    /** Stop is offered while something can still be cancelled. */
    val canStop: Boolean = true,
    /** Id of the confirmation the Approve / Deny buttons answer. */
    val confirmationId: Long? = null,
)

internal data class BubbleModel(
    val phase: AssistantPhase,
    /** The running agent step, shown on the button. */
    val step: Int?,
    /** Tasks waiting to run: queued, waiting for an unlock and scheduled. */
    val pendingTasks: Int,
    val active: Boolean,
)

/** Everything the overlays are drawn from. */
internal data class OverlayInputs(
    val state: AssistantUiState,
    val board: TaskBoard,
    val floatingButton: Boolean,
    /** A Wakey screen is showing, which already shows all of this. */
    val wakeyVisible: Boolean,
    val locked: Boolean,
    val nowMs: Long,
)

/**
 * Decides what the on-screen overlays show. The status pill follows one request from listening,
 * through thinking and each step, to its reply, then lingers briefly on the result so it never
 * blinks out mid-sentence; the floating button mirrors the phase. Nothing shows over Wakey's own
 * screens or on the lock screen.
 */
internal object OverlayModels {
    /** How long the pill keeps showing a finished request's reply once Wakey is idle again. */
    const val DONE_LINGER_MS = 2_500L

    fun pill(inputs: OverlayInputs): PillModel? {
        if (inputs.locked || inputs.wakeyVisible) return null
        val state = inputs.state
        state.pendingConfirmation?.let { return PillModel(PillMode.Confirm, it.question, confirmationId = it.id) }
        val action = state.currentAction
        val request = state.entries.lastOrNull { it.speaker == Speaker.User }?.text
        val reply = state.entries.lastOrNull()?.takeIf { it.speaker == Speaker.Wakey }
        return when (state.phase) {
            AssistantPhase.Hearing -> PillModel(PillMode.Listening, state.liveTranscript.ifBlank { LISTENING })
            AssistantPhase.Thinking, AssistantPhase.Acting ->
                if (action != null) acting(action) else PillModel(PillMode.Thinking, request ?: THINKING)
            AssistantPhase.Speaking -> reply?.let { PillModel(if (it.isError) PillMode.Failed else PillMode.Done, it.text) }
            AssistantPhase.Idle, AssistantPhase.WakeListening -> reply
                ?.takeIf { inputs.nowMs - it.timestampMs in 0 until DONE_LINGER_MS }
                ?.let { PillModel(if (it.isError) PillMode.Failed else PillMode.Done, it.text, canStop = false) }
        }
    }

    /** When [pill] will change without any new input (a lingering reply expiring), or null. */
    fun pillExpiresAt(inputs: OverlayInputs): Long? {
        val reply = inputs.state.entries.lastOrNull()?.takeIf { it.speaker == Speaker.Wakey } ?: return null
        val resting = inputs.state.phase == AssistantPhase.Idle || inputs.state.phase == AssistantPhase.WakeListening
        val expiry = reply.timestampMs + DONE_LINGER_MS
        return expiry.takeIf { resting && expiry > inputs.nowMs }
    }

    fun bubble(inputs: OverlayInputs): BubbleModel? {
        if (!inputs.floatingButton || inputs.locked || inputs.wakeyVisible) return null
        val state = inputs.state
        return BubbleModel(
            phase = state.phase,
            step = state.currentAction?.takeIf { it.maxSteps > 1 && state.phase == AssistantPhase.Acting }?.step,
            pendingTasks = inputs.board.pendingCount,
            active = state.phase in ACTIVE_PHASES || state.pendingConfirmation != null,
        )
    }

    private fun acting(action: AgentActionInfo) = PillModel(
        mode = PillMode.Acting,
        text = action.description,
        step = action.step.takeIf { action.maxSteps > 1 },
        deciding = action.result != null,
    )

    const val LISTENING = "Listening…"
    const val THINKING = "Thinking…"
    private val ACTIVE_PHASES = setOf(AssistantPhase.Hearing, AssistantPhase.Thinking, AssistantPhase.Acting, AssistantPhase.Speaking)
}
