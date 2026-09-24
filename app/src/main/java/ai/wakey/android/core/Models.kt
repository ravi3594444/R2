package ai.wakey.android.core

/** The assistant's visible state. Drives the orb animation, notification text and controls. */
enum class AssistantPhase(val label: String) {
    Idle("Idle"),
    WakeListening("Listening for wake word"),
    Hearing("Listening"),
    Thinking("Thinking"),
    Acting("Acting"),
    Speaking("Speaking"),
}

/** How a request entered the pipeline. Voice and text share everything after transcription. */
enum class InputSource(val label: String) {
    WakeWord("wake word"), Mic("mic"), PushToTalk("push-to-talk"), Assistant("assistant button"), Text("typed"),
}

enum class Speaker { User, Wakey, System }

/** Per-request latency and cost measurements, shown under each reply and in diagnostics. */
data class TurnTimings(
    val source: InputSource,
    /** Keyword audio end → wake detector fired (on-device). */
    val wakeDetectionMs: Long? = null,
    /** STT stream requested → WebSocket connected. */
    val sttConnectMs: Long? = null,
    /** Last spoken word → final transcript received. */
    val transcriptionMs: Long? = null,
    /** Stream opened → final transcript (includes the user's speaking time). */
    val speechSessionMs: Long? = null,
    /** Final transcript (or typed submit) → first Android action executed. */
    val firstActionMs: Long? = null,
    /** Final transcript (or typed submit) → reply audio started. */
    val spokenReplyMs: Long? = null,
    /** Final transcript (or typed submit) → request finished. */
    val totalMs: Long? = null,
    val route: String = "",
    val steps: Int = 0,
    val llmCalls: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)

data class ChatEntry(
    val id: Long,
    val speaker: Speaker,
    val text: String,
    val timestampMs: Long,
    val source: InputSource? = null,
    val timings: TurnTimings? = null,
    val isError: Boolean = false,
)

/** The agent step currently shown in the UI and notification. */
data class AgentActionInfo(
    val step: Int,
    val maxSteps: Int,
    /** Human-readable, e.g. "Tapping “Bluetooth”". */
    val description: String,
    val toolName: String,
    /** Null while running. */
    val result: String? = null,
    val success: Boolean? = null,
)

/** A consequential action waiting for the user's explicit approval. */
data class PendingConfirmation(
    val id: Long,
    val question: String,
    val detail: String,
)

/** Wake-word activity since Wakey started, for Diagnostics. */
data class WakeStats(
    /** Sure detections that opened the microphone stream. */
    val wakes: Int = 0,
    /** Unsure detections confirmed by the transcript. */
    val checksConfirmed: Int = 0,
    /** Unsure detections dropped because the transcript did not start with the wake phrase. */
    val checksRejected: Int = 0,
    /** What Deepgram heard for the last dropped check, for tuning. */
    val lastRejectedHeard: String? = null,
    /** Times the open microphone went silent because Android muted it. */
    val mutedEvents: Int = 0,
)

data class AssistantUiState(
    val phase: AssistantPhase = AssistantPhase.Idle,
    val wakeServiceRunning: Boolean = false,
    /** Words recognised so far in the current utterance. */
    val liveTranscript: String = "",
    val entries: List<ChatEntry> = emptyList(),
    val currentAction: AgentActionInfo? = null,
    val recentActions: List<AgentActionInfo> = emptyList(),
    val pendingConfirmation: PendingConfirmation? = null,
    /** Short status line, e.g. "No internet — using direct commands only". */
    val statusMessage: String? = null,
    val statusIsError: Boolean = false,
    /** Microphone level 0..1 for animation. */
    val micLevel: Float = 0f,
    val pushToTalkActive: Boolean = false,
    val lastTimings: TurnTimings? = null,
)
