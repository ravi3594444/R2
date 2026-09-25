package ai.wakey.android.tasks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a [TaskStore] keeps: the tasks, and the next id to hand out, since ids must never be reused. */
data class StoredTasks(val tasks: List<WakeyTask> = emptyList(), val nextId: Long = 1)

/** Keeps tasks across process restarts. */
interface TaskStore {
    fun load(): StoredTasks

    /** Replaces everything stored; may write in the background. */
    fun save(tasks: List<WakeyTask>, nextId: Long)
}

/** Wakes Wakey at a wall-clock time. There is one wake-up at a time: the harness always asks for the next. */
fun interface TaskWakeups {
    /** Replaces the earlier wake-up; null cancels it. [ringing] marks a user-visible alarm, which must not be late. */
    fun wakeAt(epochMs: Long?, ringing: Boolean)

    /** Forgets what was requested, so the next [wakeAt] registers again even if unchanged (the alarm fired). */
    fun reset() = Unit
}

/**
 * The bookkeeping half of Wakey's task harness: what is running, what runs after it ("… after
 * this"), what is scheduled, what waits for the phone to be unlocked, and what finished. The
 * controller decides how to run things; this class only records them, persists them and asks for a
 * wake-up at the next due time. Every change publishes a new [board].
 *
 * Main thread only.
 */
class TaskHarness(
    private val store: TaskStore,
    private val wakeups: TaskWakeups,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val tasks = mutableListOf<WakeyTask>()

    /**
     * Never reused, even after tasks are cleared and Wakey restarts: notification buttons refer to
     * tasks by id, and an old "Run now" must not start a newer task.
     */
    private var nextId = 1L
    private var batchDepth = 0
    private var dirty = false

    private val _board = MutableStateFlow(TaskBoard())
    val board: StateFlow<TaskBoard> = _board.asStateFlow()

    /**
     * Restores stored tasks. A task that was running (or waiting its turn after one) when Wakey was
     * closed can't pick up where it left off, so it is recorded as interrupted; a scheduled task whose
     * time came while it was queued goes back to the schedule, where [dueNow] finds it.
     */
    fun load() {
        val now = clock()
        val stored = store.load()
        tasks.clear()
        for (task in stored.tasks) {
            tasks += when (task.status) {
                TaskStatus.Running -> task.copy(status = TaskStatus.Failed, finishedAtMs = now, result = INTERRUPTED)
                TaskStatus.Queued ->
                    if (task.dueAtMs != null) task.copy(status = TaskStatus.Scheduled)
                    else task.copy(status = TaskStatus.Cancelled, finishedAtMs = now, result = INTERRUPTED)
                else -> task
            }
        }
        nextId = maxOf(stored.nextId, (tasks.maxOfOrNull { it.id } ?: 0L) + 1)
        publish()
    }

    /** Runs [block], publishing (and persisting) once at the end however many changes it makes. */
    fun <T> batch(block: () -> T): T {
        batchDepth++
        try {
            return block()
        } finally {
            batchDepth--
            if (batchDepth == 0 && dirty) publish()
        }
    }

    fun find(id: Long): WakeyTask? = tasks.firstOrNull { it.id == id }

    /** Requests the next wake-up again; call when the previous one fired, since it may have fired early. */
    fun rearm() {
        wakeups.reset()
        publish()
    }

    /** Records [text] as running now. */
    fun start(text: String, kind: TaskKind = TaskKind.Task): WakeyTask {
        val now = clock()
        return add(WakeyTask(nextId++, text, kind, TaskStatus.Running, createdAtMs = now, startedAtMs = now))
    }

    /** Queues [text] to run after the current task and anything already queued. */
    fun enqueue(text: String): WakeyTask =
        add(WakeyTask(nextId++, text, TaskKind.Task, TaskStatus.Queued, createdAtMs = clock(), deferred = true))

    fun schedule(text: String, kind: TaskKind, dueAtMs: Long): WakeyTask =
        add(WakeyTask(nextId++, text, kind, TaskStatus.Scheduled, createdAtMs = clock(), dueAtMs = dueAtMs, deferred = true))

    /** Scheduled tasks whose time has come, soonest first. The caller runs, queues, parks or finishes each. */
    fun dueNow(): List<WakeyTask> {
        val now = clock()
        return tasks.filter { it.status == TaskStatus.Scheduled && (it.dueAtMs ?: Long.MAX_VALUE) <= now + DUE_TOLERANCE_MS }
            .sortedBy { it.dueAtMs }
    }

    /** The next queued task, if any. */
    fun nextQueued(): WakeyTask? = tasks.firstOrNull { it.status == TaskStatus.Queued }

    /** Moves [id] to the end of the queue (a due scheduled task waiting its turn). */
    fun queue(id: Long): WakeyTask? = move(id, toFront = false) { it.copy(status = TaskStatus.Queued) }

    /** Queues [id] ahead of everything else ("Run now" on a notification or in the task list). */
    fun runNext(id: Long): WakeyTask? {
        val task = find(id) ?: return null
        if (task.status == TaskStatus.Running) return task
        return move(id, toFront = true) { it.copy(status = TaskStatus.Queued, finishedAtMs = null, result = null, deferred = true) }
    }

    fun markRunning(id: Long): WakeyTask? = update(id) {
        if (it.status.finished) null else it.copy(status = TaskStatus.Running, startedAtMs = clock())
    }

    /** Parks a due task until the phone is unlocked. */
    fun markWaiting(id: Long): WakeyTask? = update(id) {
        if (it.status.finished) null else it.copy(status = TaskStatus.WaitingForUnlock)
    }

    /** The phone was unlocked: waiting tasks join the queue, oldest first. */
    fun releaseWaiting(): List<WakeyTask> = batch {
        tasks.filter { it.status == TaskStatus.WaitingForUnlock }.sortedBy { it.dueAtMs }.mapNotNull { queue(it.id) }
    }

    /** Tasks that waited too long for an unlock become [TaskStatus.Missed]; returns them. */
    fun expireWaiting(): List<WakeyTask> = batch {
        val now = clock()
        tasks.filter { it.status == TaskStatus.WaitingForUnlock && (it.dueAtMs ?: now) + WAIT_FOR_UNLOCK_MS <= now }
            .mapNotNull { finish(it.id, TaskStatus.Missed, MISSED_LOCKED) }
    }

    /** Ends [id] with [status] (which must be a finished status); ignored if it already finished. */
    fun finish(id: Long, status: TaskStatus, result: String?): WakeyTask? {
        require(status.finished) { "$status is not a finished status" }
        return update(id) {
            if (it.status.finished) null else it.copy(status = status, finishedAtMs = clock(), result = result?.takeIf(String::isNotBlank))
        }
    }

    /** Cancels one unfinished task. */
    fun cancel(id: Long): WakeyTask? = finish(id, TaskStatus.Cancelled, null)

    /** Cancels everything queued to run after the current task (Stop). */
    fun clearQueue(): List<WakeyTask> = batch {
        tasks.filter { it.status == TaskStatus.Queued }.mapNotNull { cancel(it.id) }
    }

    /**
     * Cancels scheduled and waiting tasks: all of them ([all]) or the soonest, only of [kind] when
     * given and only those whose text contains [query] when given.
     */
    fun cancelScheduled(kind: TaskKind?, all: Boolean, query: String?): List<WakeyTask> {
        val needle = query?.trim()?.lowercase()?.ifEmpty { null }
        val matching = tasks
            .filter { it.status == TaskStatus.Scheduled || it.status == TaskStatus.WaitingForUnlock }
            .filter { kind == null || it.kind == kind }
            .filter { needle == null || it.text.lowercase().contains(needle) }
            .sortedBy { it.dueAtMs }
        return batch { (if (all) matching else matching.take(1)).mapNotNull { cancel(it.id) } }
    }

    /** Schedules a copy of [id] [delayMs] from now (Snooze); a waiting or scheduled original is closed. */
    fun snooze(id: Long, delayMs: Long): WakeyTask? {
        val original = find(id) ?: return null
        return batch {
            if (!original.status.finished) finish(id, TaskStatus.Done, SNOOZED)
            schedule(original.text, original.kind, clock() + delayMs)
        }
    }

    /** Forgets finished tasks. */
    fun clearRecent() {
        tasks.removeAll { it.status.finished }
        publish()
    }

    private fun add(task: WakeyTask): WakeyTask {
        tasks += task
        publish()
        return task
    }

    private inline fun update(id: Long, change: (WakeyTask) -> WakeyTask?): WakeyTask? {
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = change(tasks[index]) ?: return null
        tasks[index] = updated
        publish()
        return updated
    }

    private inline fun move(id: Long, toFront: Boolean, change: (WakeyTask) -> WakeyTask): WakeyTask? {
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = change(tasks.removeAt(index))
        if (toFront) tasks.add(0, updated) else tasks += updated
        publish()
        return updated
    }

    private fun publish() {
        if (batchDepth > 0) {
            dirty = true
            return
        }
        dirty = false
        val finished = tasks.filter { it.status.finished }.sortedByDescending { it.finishedAtMs ?: it.createdAtMs }
        if (finished.size > RECENT_LIMIT) {
            val dropped = finished.drop(RECENT_LIMIT).mapTo(HashSet()) { it.id }
            tasks.removeAll { it.id in dropped }
        }
        val scheduled = tasks.filter { it.status == TaskStatus.Scheduled }.sortedBy { it.dueAtMs }
        val waiting = tasks.filter { it.status == TaskStatus.WaitingForUnlock }
        _board.value = TaskBoard(
            // A replaced task finishes a moment after its successor starts; show the newest.
            running = tasks.filter { it.status == TaskStatus.Running }.maxWithOrNull(compareBy({ it.startedAtMs ?: 0L }, { it.id })),
            upNext = tasks.filter { it.status == TaskStatus.Queued },
            waiting = waiting,
            scheduled = scheduled,
            recent = finished.take(RECENT_LIMIT),
        )
        val nextDue = scheduled.firstOrNull()
        val nextExpiry = waiting.mapNotNull { it.dueAtMs }.minOrNull()?.plus(WAIT_FOR_UNLOCK_MS)
        val wakeAt = listOfNotNull(nextDue?.dueAtMs, nextExpiry).minOrNull()
        wakeups.wakeAt(wakeAt, ringing = nextDue?.kind == TaskKind.Alarm && nextDue.dueAtMs == wakeAt)
        store.save(tasks.toList(), nextId)
    }

    companion object {
        /** How long a due task waits for the phone to be unlocked before it counts as missed. */
        const val WAIT_FOR_UNLOCK_MS = 30 * 60_000L

        /** A task this late (the phone was off, or Wakey was stopped) is reported missed instead of run. */
        const val MISSED_AFTER_MS = 30 * 60_000L

        /** Alarms may fire a moment early; treat tasks due within this as due. */
        private const val DUE_TOLERANCE_MS = 1_000L
        private const val RECENT_LIMIT = 20

        const val INTERRUPTED = "Interrupted when Wakey was closed."
        const val MISSED_LOCKED = "The phone stayed locked."
        const val SNOOZED = "Snoozed."
    }
}
