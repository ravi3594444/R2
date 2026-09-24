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
    WakeWord("wake word"),
    Mic("mic"),
    PushToTalk("push-to-talk"),
    Text("typed"),

    /** The floating Wakey button over other apps. */
    Button("floating button"),

    /** A task queued with "… after this", now running. */
    Queued("after the last task"),

    /** A task whose scheduled time came. */
    Scheduled("scheduled"),
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

data class AssistantUiState(
    val phase: AssistantPhase = AssistantPhase.Idle,
    /** The voice service runs, so the microphone can be used from the background. */
    val wakeServiceRunning: Boolean = false,
    /** The on-device wake word detector is on (the service may also run just for the floating button). */
    val wakeWordEnabled: Boolean = false,
    /** Words recognised so far in the current utterance. */
    val liveTranscript: String = "",
    val entries: List<ChatEntry> = emptyList(),
    val currentAction: AgentActionInfo? = null,
    val recentActions: List<AgentActionInfo> = emptyList(),
    /** The user entry whose task [recentActions] belong to; the step list is shown under it. */
    val taskEntryId: Long? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    /** Short status line, e.g. "No internet — using direct commands only". */
    val statusMessage: String? = null,
    val statusIsError: Boolean = false,
    /** Microphone level 0..1 for animation. */
    val micLevel: Float = 0f,
    val pushToTalkActive: Boolean = false,
    val lastTimings: TurnTimings? = null,
)
