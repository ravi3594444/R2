package ai.wakey.android.overlay

import ai.wakey.android.core.AgentActionInfo
import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.core.ChatEntry
import ai.wakey.android.core.PendingConfirmation
import ai.wakey.android.core.Speaker
import ai.wakey.android.tasks.TaskBoard
import ai.wakey.android.tasks.TaskKind
import ai.wakey.android.tasks.TaskStatus
import ai.wakey.android.tasks.WakeyTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayModelsTest {
    private val now = 100_000L

    private fun inputs(
        state: AssistantUiState,
        board: TaskBoard = TaskBoard(),
        floatingButton: Boolean = true,
        wakeyVisible: Boolean = false,
        locked: Boolean = false,
        nowMs: Long = now,
    ) = OverlayInputs(state, board, floatingButton, wakeyVisible, locked, nowMs)

    private fun entry(speaker: Speaker, text: String, at: Long = now, error: Boolean = false) =
        ChatEntry(at, speaker, text, at, isError = error)

    private fun action(step: Int, description: String, result: String? = null, maxSteps: Int = 12) =
        AgentActionInfo(step, maxSteps, description, "tap", result, result?.let { true })

    @Test
    fun followsARequestFromListeningToTheReply() {
        assertEquals(
            PillModel(PillMode.Listening, "call mum"),
            OverlayModels.pill(inputs(AssistantUiState(phase = AssistantPhase.Hearing, liveTranscript = "call mum"))),
        )
        assertEquals(
            PillModel(PillMode.Listening, OverlayModels.LISTENING),
            OverlayModels.pill(inputs(AssistantUiState(phase = AssistantPhase.Hearing))),
        )
        // The request stays on screen while Wakey thinks about the first step.
        val asked = listOf(entry(Speaker.User, "open Settings and find Bluetooth"))
        assertEquals(
            PillModel(PillMode.Thinking, "open Settings and find Bluetooth"),
            OverlayModels.pill(inputs(AssistantUiState(phase = AssistantPhase.Thinking, entries = asked))),
        )
        assertEquals(
            PillModel(PillMode.Acting, "Tapping “Bluetooth”", step = 2),
            OverlayModels.pill(inputs(AssistantUiState(phase = AssistantPhase.Acting, currentAction = action(2, "Tapping “Bluetooth”")))),
        )
        // Between steps the finished step stays, marked as deciding the next.
        assertEquals(
            PillModel(PillMode.Acting, "Tapping “Bluetooth”", step = 2, deciding = true),
            OverlayModels.pill(inputs(AssistantUiState(phase = AssistantPhase.Acting, currentAction = action(2, "Tapping “Bluetooth”", "Tapped")))),
        )
        val replied = asked + entry(Speaker.Wakey, "Bluetooth settings are open.")
        assertEquals(
            PillModel(PillMode.Done, "Bluetooth settings are open."),
            OverlayModels.pill(inputs(AssistantUiState(phase = AssistantPhase.Speaking, entries = replied))),
        )
    }

    @Test
    fun fastCommandsHaveNoStepNumber() {
        val torch = AgentActionInfo(1, 1, "Turning the flashlight on", "fast_command")
        assertEquals(
            PillModel(PillMode.Acting, "Turning the flashlight on"),
            OverlayModels.pill(inputs(AssistantUiState(phase = AssistantPhase.Acting, currentAction = torch))),
        )
    }

    @Test
    fun theReplyLingersBrieflyOnceIdle() {
        val state = AssistantUiState(phase = AssistantPhase.Idle, entries = listOf(entry(Speaker.Wakey, "Flashlight is on.", at = now - 1_000)))
        assertEquals(PillModel(PillMode.Done, "Flashlight is on.", canStop = false), OverlayModels.pill(inputs(state)))
        assertEquals(now - 1_000 + OverlayModels.DONE_LINGER_MS, OverlayModels.pillExpiresAt(inputs(state)))
        val later = inputs(state, nowMs = now - 1_000 + OverlayModels.DONE_LINGER_MS)
        assertNull(OverlayModels.pill(later))
        assertNull(OverlayModels.pillExpiresAt(later))
    }

    @Test
    fun failuresShowAsFailed() {
        val state = AssistantUiState(phase = AssistantPhase.Speaking, entries = listOf(entry(Speaker.Wakey, "I couldn't find that app.", error = true)))
        assertEquals(PillMode.Failed, OverlayModels.pill(inputs(state))?.mode)
    }

    @Test
    fun confirmationsAskInThePill() {
        val state = AssistantUiState(
            phase = AssistantPhase.Speaking,
            currentAction = action(3, "Tapping “Send”"),
            pendingConfirmation = PendingConfirmation(7, "Tap “Send” in WhatsApp?", "detail"),
        )
        assertEquals(PillModel(PillMode.Confirm, "Tap “Send” in WhatsApp?", confirmationId = 7), OverlayModels.pill(inputs(state)))
    }

    @Test
    fun nothingShowsOverWakeyOrOnTheLockScreen() {
        val hearing = AssistantUiState(phase = AssistantPhase.Hearing)
        assertNull(OverlayModels.pill(inputs(hearing, wakeyVisible = true)))
        assertNull(OverlayModels.pill(inputs(hearing, locked = true)))
        assertNull(OverlayModels.bubble(inputs(hearing, wakeyVisible = true)))
        assertNull(OverlayModels.bubble(inputs(hearing, locked = true)))
        assertNull(OverlayModels.pill(inputs(AssistantUiState(phase = AssistantPhase.WakeListening))))
    }

    @Test
    fun theButtonMirrorsThePhaseAndCountsPendingTasks() {
        val board = TaskBoard(
            upNext = listOf(WakeyTask(1, "play music", status = TaskStatus.Queued, createdAtMs = 0)),
            scheduled = listOf(WakeyTask(2, "call mum", TaskKind.Task, TaskStatus.Scheduled, createdAtMs = 0, dueAtMs = 1)),
        )
        assertEquals(
            BubbleModel(AssistantPhase.Idle, step = null, pendingTasks = 2, active = false),
            OverlayModels.bubble(inputs(AssistantUiState(), board)),
        )
        assertEquals(
            BubbleModel(AssistantPhase.Acting, step = 4, pendingTasks = 0, active = true),
            OverlayModels.bubble(inputs(AssistantUiState(phase = AssistantPhase.Acting, currentAction = action(4, "Scrolling down")))),
        )
        assertNull(OverlayModels.bubble(inputs(AssistantUiState(), floatingButton = false)))
    }
}
