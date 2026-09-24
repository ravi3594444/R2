package ai.wakey.android.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHarnessTest {
    private var now = 1_000_000L

    private class MemoryStore(var stored: List<WakeyTask> = emptyList()) : TaskStore {
        override fun load() = stored
        override fun save(tasks: List<WakeyTask>) {
            stored = tasks
        }
    }

    private val store = MemoryStore()
    private val wakes = mutableListOf<Pair<Long?, Boolean>>()
    private val harness = TaskHarness(store, { at, ringing -> wakes += at to ringing }) { now }

    @Test
    fun startedTaskRunsAndFinishes() {
        val task = harness.start("open YouTube")
        assertEquals(task, harness.board.value.running)
        harness.finish(task.id, TaskStatus.Done, "Opened YouTube.")
        val board = harness.board.value
        assertNull(board.running)
        assertEquals(listOf(TaskStatus.Done), board.recent.map { it.status })
        assertEquals("Opened YouTube.", board.recent.single().result)
        // Finishing twice keeps the first outcome.
        assertNull(harness.finish(task.id, TaskStatus.Failed, "late"))
    }

    @Test
    fun queuedTasksRunInOrder() {
        harness.start("open YouTube")
        val first = harness.enqueue("play despacito")
        val second = harness.enqueue("turn off the flashlight")
        assertEquals(listOf(first.id, second.id), harness.board.value.upNext.map { it.id })
        assertEquals(first.id, harness.nextQueued()?.id)
        harness.markRunning(first.id)
        assertEquals(first.id, harness.board.value.running?.id)
        assertEquals(listOf(second.id), harness.board.value.upNext.map { it.id })
        assertTrue(first.deferred)
    }

    @Test
    fun theNextWakeUpIsTheSoonestTask() {
        harness.schedule("call mum", TaskKind.Task, now + 60_000)
        assertEquals(now + 60_000 to false, wakes.last())
        harness.schedule("Wake up", TaskKind.Alarm, now + 30_000)
        assertEquals(now + 30_000 to true, wakes.last())
        val soonest = harness.board.value.scheduled.first()
        harness.cancel(soonest.id)
        assertEquals(now + 60_000 to false, wakes.last())
        harness.cancel(harness.board.value.scheduled.single().id)
        assertEquals(null to false, wakes.last())
    }

    @Test
    fun dueTasksAreFoundAndQueued() {
        val call = harness.schedule("call mum", TaskKind.Task, now + 10_000)
        val later = harness.schedule("call dad", TaskKind.Task, now + 90_000)
        assertTrue(harness.dueNow().isEmpty())
        now += 10_000
        assertEquals(listOf(call.id), harness.dueNow().map { it.id })
        harness.queue(call.id)
        assertEquals(listOf(call.id), harness.board.value.upNext.map { it.id })
        assertEquals(listOf(later.id), harness.board.value.scheduled.map { it.id })
        assertTrue(harness.dueNow().isEmpty())
    }

    @Test
    fun waitingTasksRunOnUnlockOrExpire() {
        val call = harness.schedule("call mum", TaskKind.Task, now)
        harness.markWaiting(call.id)
        assertEquals(listOf(call.id), harness.board.value.waiting.map { it.id })
        // The wake-up now is the moment it stops waiting.
        assertEquals(now + TaskHarness.WAIT_FOR_UNLOCK_MS to false, wakes.last())
        assertEquals(listOf(call.id), harness.releaseWaiting().map { it.id })
        assertEquals(TaskStatus.Queued, harness.find(call.id)?.status)

        val text = harness.schedule("text dad", TaskKind.Task, now)
        harness.markWaiting(text.id)
        now += TaskHarness.WAIT_FOR_UNLOCK_MS - 1
        assertTrue(harness.expireWaiting().isEmpty())
        now += 1
        val missed = harness.expireWaiting().single()
        assertEquals(TaskStatus.Missed, missed.status)
        assertEquals(TaskHarness.MISSED_LOCKED, missed.result)
    }

    @Test
    fun cancelScheduledByKindAndWords() {
        harness.schedule("call mum", TaskKind.Task, now + 3_000)
        harness.schedule("drink water", TaskKind.Reminder, now + 2_000)
        harness.schedule("stretch", TaskKind.Reminder, now + 1_000)
        harness.schedule("", TaskKind.Alarm, now + 4_000)
        assertEquals(listOf("drink water"), harness.cancelScheduled(TaskKind.Reminder, all = false, query = "WATER").map { it.text })
        assertEquals(listOf("stretch"), harness.cancelScheduled(TaskKind.Reminder, all = false, query = null).map { it.text })
        assertTrue(harness.cancelScheduled(TaskKind.Reminder, all = true, query = null).isEmpty())
        assertEquals(2, harness.cancelScheduled(null, all = true, query = null).size)
        assertTrue(harness.board.value.scheduled.isEmpty())
    }

    @Test
    fun stopClearsTheQueueButNotTheSchedule() {
        harness.start("open YouTube")
        harness.enqueue("play despacito")
        harness.schedule("call mum", TaskKind.Task, now + 60_000)
        assertEquals(1, harness.clearQueue().size)
        assertTrue(harness.board.value.upNext.isEmpty())
        assertEquals(1, harness.board.value.scheduled.size)
    }

    @Test
    fun snoozeSchedulesACopy() {
        val alarm = harness.schedule("Wake up", TaskKind.Alarm, now)
        harness.finish(alarm.id, TaskStatus.Done, "Rang.")
        val snoozed = harness.snooze(alarm.id, 600_000)!!
        assertEquals(TaskKind.Alarm, snoozed.kind)
        assertEquals(now + 600_000, snoozed.dueAtMs)
        assertEquals(TaskStatus.Scheduled, snoozed.status)
        assertEquals("Rang.", harness.find(alarm.id)?.result)
    }

    @Test
    fun runNextReopensAMissedTaskAtTheFront() {
        harness.enqueue("play despacito")
        val call = harness.schedule("call mum", TaskKind.Task, now)
        harness.finish(call.id, TaskStatus.Missed, "late")
        harness.runNext(call.id)
        assertEquals(listOf("call mum", "play despacito"), harness.board.value.upNext.map { it.text })
        assertNull(harness.find(call.id)?.result)
    }

    @Test
    fun loadRestoresWhatCanContinue() {
        store.stored = listOf(
            WakeyTask(4, "open YouTube", status = TaskStatus.Running, createdAtMs = 1),
            WakeyTask(7, "play despacito", status = TaskStatus.Queued, createdAtMs = 1, deferred = true),
            WakeyTask(9, "call mum", status = TaskStatus.Queued, createdAtMs = 1, dueAtMs = 5, deferred = true),
            WakeyTask(12, "drink water", TaskKind.Reminder, TaskStatus.Scheduled, createdAtMs = 1, dueAtMs = now + 5_000),
        )
        harness.load()
        val board = harness.board.value
        assertNull(board.running)
        assertEquals(listOf("call mum", "drink water"), board.scheduled.map { it.text })
        assertEquals(setOf(TaskStatus.Failed, TaskStatus.Cancelled), board.recent.map { it.status }.toSet())
        assertTrue(board.recent.all { it.result == TaskHarness.INTERRUPTED })
        assertEquals(13, harness.enqueue("next").id)
        assertEquals(listOf("call mum"), harness.dueNow().map { it.text })
    }

    @Test
    fun recentTasksAreBounded() {
        repeat(30) { i ->
            now += 1
            val task = harness.start("task $i")
            harness.finish(task.id, TaskStatus.Done, null)
        }
        val recent = harness.board.value.recent
        assertEquals(20, recent.size)
        assertEquals("task 29", recent.first().text)
        assertEquals(20, store.stored.size)
    }

    @Test
    fun jsonRoundTrip() {
        val tasks = listOf(
            WakeyTask(1, "call mum", TaskKind.Task, TaskStatus.Scheduled, createdAtMs = 10, dueAtMs = 20, deferred = true),
            WakeyTask(2, "", TaskKind.Alarm, TaskStatus.Done, createdAtMs = 11, dueAtMs = 21, startedAtMs = 22, finishedAtMs = 23, result = "Rang."),
            WakeyTask(3, "मम्मी को फोन करना \"जल्दी\"", TaskKind.Reminder, TaskStatus.WaitingForUnlock, createdAtMs = 12),
        )
        assertEquals(tasks, TaskJson.decode(TaskJson.encode(tasks)))
    }

    @Test
    fun brokenJsonIsSkipped() {
        assertTrue(TaskJson.decode("not json").isEmpty())
        assertTrue(TaskJson.decode("{}").isEmpty())
        val json = """{"version":1,"tasks":[{"id":1,"text":"ok","kind":"Task","status":"Done","created":1},""" +
            """{"id":2,"text":"bad","kind":"Nope","status":"Done"},{"text":"no id","kind":"Task","status":"Done"}]}"""
        assertEquals(listOf("ok"), TaskJson.decode(json).map { it.text })
    }
}
