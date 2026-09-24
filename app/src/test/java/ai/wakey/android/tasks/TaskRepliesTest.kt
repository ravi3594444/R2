package ai.wakey.android.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class TaskRepliesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 9, 24, 14, 0, 0, 0, zone)

    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone)

    private fun reply(heard: String) = TaskReplies.scheduled(TaskParser.parse(heard, now) as TaskRequest.Scheduled, now, heard)

    private fun task(id: Long, text: String, kind: TaskKind, due: ZonedDateTime, status: TaskStatus = TaskStatus.Scheduled) =
        WakeyTask(id, text, kind, status, createdAtMs = 0, dueAtMs = due.toInstant().toEpochMilli())

    @Test
    fun confirmsScheduledRequests() {
        assertEquals("Okay, I'll call mum at 4 PM.", reply("call mum at 4"))
        assertEquals("Okay, I'll call your mum tomorrow at 9 AM.", reply("Call my mum tomorrow at 9"))
        assertEquals("Okay, I'll open YouTube on Monday at 10:30 AM.", reply("open YouTube on monday at 10:30 am"))
        assertEquals("Okay, I'll play despacito in 10 minutes.", reply("play despacito in 10 minutes"))
        assertEquals("Okay, I'll remind you to drink water at 5 PM.", reply("remind me to drink water at 5"))
        assertEquals("Okay, I'll remind you about the meeting at 3 PM.", reply("remind me about the meeting at 3"))
        assertEquals("Okay, I'll remind you in 10 minutes: paani peena hai.", reply("10 minute baad yaad dilana ki paani peena hai"))
        assertEquals("Okay, I'll wake you up tomorrow at 6 AM.", reply("wake me up at 6"))
        assertEquals("Alarm set. I'll ring at 5 PM.", reply("ring me at 5"))
        assertEquals("Timer set. I'll ring in 10 minutes.", reply("set a timer for 10 minutes"))
    }

    @Test
    fun hindiRequestsGetHindiReplies() {
        assertEquals("ठीक है, शाम 4 बजे यह करूँगा: मम्मी को फोन करना।", reply("शाम 4 बजे मम्मी को फोन करना"))
        assertEquals("ठीक है, कल सुबह 7 बजे अलार्म बजेगा।", reply("कल सुबह 7 बजे उठा देना"))
        assertEquals("ठीक है, 10 मिनट में यह करूँगा: टॉर्च बंद करो।", reply("10 मिनट बाद टॉर्च बंद करो"))
    }

    @Test
    fun queuedReply() {
        assertEquals("Okay, after this I'll play this song.", TaskReplies.queued("Play this song"))
        assertEquals("Okay, after this I'll open YouTube.", TaskReplies.queued("open YouTube"))
        assertEquals("ठीक है, इसके बाद यह करूँगा: गाना चलाओ।", TaskReplies.queued("गाना चलाओ"))
    }

    @Test
    fun listsTheBoard() {
        assertEquals("You have nothing scheduled.", TaskReplies.list(TaskBoard(), now))
        val board = TaskBoard(
            running = WakeyTask(1, "open YouTube", status = TaskStatus.Running, createdAtMs = 0),
            upNext = listOf(WakeyTask(2, "play my playlist", status = TaskStatus.Queued, createdAtMs = 0)),
            scheduled = listOf(
                task(3, "call mum", TaskKind.Task, at(24, 16)),
                task(4, "Wake up", TaskKind.Alarm, at(25, 6)),
                task(5, "drink water", TaskKind.Reminder, at(25, 11)),
                task(6, "call dad", TaskKind.Task, at(26, 10)),
            ),
        )
        assertEquals(
            "Right now I'm working on: open YouTube. Next: play your playlist. You have 4 things scheduled: " +
                "call mum at 4 PM, an alarm tomorrow at 6 AM and a reminder to drink water tomorrow at 11 AM, and 1 more.",
            TaskReplies.list(board, now),
        )
    }

    @Test
    fun cancelReplies() {
        val request = TaskRequest.CancelScheduled(TaskKind.Reminder, all = false, query = "yoga")
        assertEquals("I couldn't find a scheduled reminder about yoga.", TaskReplies.cancelled(emptyList(), request, now))
        assertEquals(
            "There's no scheduled alarm to cancel.",
            TaskReplies.cancelled(emptyList(), TaskRequest.CancelScheduled(TaskKind.Alarm, all = false, query = null), now),
        )
        assertEquals(
            "Cancelled a reminder to drink water at 5 PM.",
            TaskReplies.cancelled(listOf(task(1, "drink water", TaskKind.Reminder, at(24, 17))), request, now),
        )
        assertEquals(
            "Cancelled 2 scheduled tasks.",
            TaskReplies.cancelled(
                listOf(task(1, "a", TaskKind.Task, at(24, 17)), task(2, "b", TaskKind.Alarm, at(24, 18))),
                TaskRequest.CancelScheduled(null, all = true, query = null),
                now,
            ),
        )
    }

    @Test
    fun secondPerson() {
        assertEquals("call your mum", TaskReplies.secondPerson("call my mum"))
        assertEquals("tell you a joke", TaskReplies.secondPerson("tell me a joke"))
        assertEquals("Your playlist", TaskReplies.secondPerson("My playlist"))
        assertEquals("send you the mines report", TaskReplies.secondPerson("send me the mines report"))
    }

    @Test
    fun sentenceCaseKeepsNames() {
        assertEquals("call Mum", TaskReplies.sentenceCase("Call Mum"))
        assertEquals("YouTube kholo", TaskReplies.sentenceCase("YouTube kholo"))
        assertEquals("WhatsApp mum", TaskReplies.sentenceCase("WhatsApp mum"))
    }

    @Test
    fun timeFormats() {
        assertEquals("4 PM", TaskTime.clock(at(24, 16)))
        assertEquals("4:05 PM", TaskTime.clock(at(24, 16, 5)))
        assertEquals("12:30 AM", TaskTime.clock(at(24, 0, 30)))
        assertEquals("12 PM", TaskTime.clock(at(24, 12)))
        assertEquals("4:00 PM", TaskTime.label(at(24, 16), now))
        assertEquals("Tomorrow 6:30 AM", TaskTime.label(at(25, 6, 30), now))
        assertEquals("Mon 10:00 AM", TaskTime.label(at(28, 10), now))
        assertEquals("3 Oct 9:00 AM", TaskTime.label(ZonedDateTime.of(2026, 10, 3, 9, 0, 0, 0, zone), now))
        assertEquals("in 12 min", TaskTime.countdown(at(24, 14, 12), now))
        assertEquals("in 2 h 5 min", TaskTime.countdown(at(24, 16, 5), now))
        assertEquals("in 30 s", TaskTime.countdown(now.plusSeconds(30), now))
        assertNull(TaskTime.countdown(now.minusMinutes(1), now))
        assertEquals("1 hour 30 minutes", TaskTime.duration(Duration.ofMinutes(90)))
        assertEquals("1 minute", TaskTime.duration(Duration.ofSeconds(60)))
        assertEquals("on 3 October at 9 AM", TaskTime.spoken(ZonedDateTime.of(2026, 10, 3, 9, 0, 0, 0, zone), now))
        assertEquals("at 7:15 PM", TaskTime.spoken(at(24, 19, 15), now, Duration.ofMinutes(315)))
    }
}
