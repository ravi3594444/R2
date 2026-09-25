package ai.wakey.android.tasks

/** What a task does when its time comes. */
enum class TaskKind(val label: String) {
    /** A request Wakey carries out on the phone, e.g. "call mum". */
    Task("Task"),

    /** A notification, also spoken, with what to remember, e.g. "drink water". */
    Reminder("Reminder"),

    /** Rings until it is stopped or snoozed. */
    Alarm("Alarm"),
}

enum class TaskStatus(val label: String, val finished: Boolean) {
    /** Waiting for its time. */
    Scheduled("Scheduled", false),

    /** Runs after the current task ("… after this"), or its time has come and it waits for its turn. */
    Queued("Up next", false),

    /** Its time has come but the phone is locked; runs once it is unlocked. */
    WaitingForUnlock("Waiting for unlock", false),
    Running("Running", false),
    Done("Done", true),
    Failed("Didn't finish", true),
    Cancelled("Cancelled", true),

    /** Its time passed while it could not run. */
    Missed("Missed", true),
}

/**
 * One unit of work in the [TaskHarness]: something to do now, after the current task, or at a time.
 * Times are wall-clock epoch milliseconds.
 */
data class WakeyTask(
    val id: Long,
    /** The request ("call mum"), what to remind about, or an alarm's label (may be empty). */
    val text: String,
    val kind: TaskKind = TaskKind.Task,
    val status: TaskStatus,
    val createdAtMs: Long,
    val dueAtMs: Long? = null,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    /** The spoken reply or what happened, once finished. */
    val result: String? = null,
    /** True for tasks that ran (or will run) without the user asking at that moment. */
    val deferred: Boolean = false,
) {
    /** What to show in lists: the request, or a readable name for an unlabelled alarm. */
    val title: String get() = text.ifBlank { kind.label }
}

/** Everything the harness knows, as the UI shows it. */
data class TaskBoard(
    val running: WakeyTask? = null,
    /** Tasks that run once the current one finishes, in order. */
    val upNext: List<WakeyTask> = emptyList(),
    /** Due, but the phone must be unlocked first. */
    val waiting: List<WakeyTask> = emptyList(),
    /** Future tasks, soonest first. */
    val scheduled: List<WakeyTask> = emptyList(),
    /** Finished tasks, newest first. */
    val recent: List<WakeyTask> = emptyList(),
) {
    /** Tasks that have not run yet. */
    val pendingCount: Int get() = upNext.size + waiting.size + scheduled.size

    val isEmpty: Boolean get() = running == null && pendingCount == 0 && recent.isEmpty()
}
