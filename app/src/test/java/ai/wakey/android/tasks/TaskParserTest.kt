package ai.wakey.android.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class TaskParserTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    /** Thursday 24 September 2026, 2:00 PM. */
    private val now = ZonedDateTime.of(2026, 9, 24, 14, 0, 0, 0, zone)

    private fun parse(text: String) = TaskParser.parse(text, now)

    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone)

    private fun assertScheduled(text: String, expectedText: String, kind: TaskKind, expectedAt: ZonedDateTime) {
        val request = parse(text)
        assertTrue("\"$text\" → $request", request is TaskRequest.Scheduled)
        request as TaskRequest.Scheduled
        assertEquals("text of \"$text\"", expectedText, request.text)
        assertEquals("kind of \"$text\"", kind, request.kind)
        assertEquals("time of \"$text\"", expectedAt, request.at)
    }

    private fun assertTask(text: String, expectedText: String, expectedAt: ZonedDateTime) =
        assertScheduled(text, expectedText, TaskKind.Task, expectedAt)

    private fun assertNow(vararg texts: String) {
        for (text in texts) assertEquals("\"$text\"", TaskRequest.Now(text.trim()), parse(text))
    }

    @Test
    fun taskAtAnHour() {
        assertTask("call mum at 4", "call mum", at(24, 16))
        assertTask("Call Mum at 4 pm.", "Call Mum", at(24, 16))
        assertTask("call mum at 4:30", "call mum", at(24, 16, 30))
        assertTask("call mum at 16:30", "call mum", at(24, 16, 30))
        assertTask("call mum at 4pm", "call mum", at(24, 16))
        assertTask("call mum at 4 p.m.", "call mum", at(24, 16))
        assertTask("call mum 4 o'clock", "call mum", at(24, 16))
        assertTask("call mum at four", "call mum", at(24, 16))
        assertTask("hey wakey, call mum at 4 please", "call mum", at(24, 16))
        assertTask("Can you call mum at 4?", "call mum", at(24, 16))
    }

    @Test
    fun timeFirst() {
        assertTask("at 4, call mum", "call mum", at(24, 16))
        assertTask("At 4 pm call mum", "call mum", at(24, 16))
        assertTask("at 4 and then call mum", "call mum", at(24, 16))
        assertTask("tomorrow at 9 open YouTube", "open YouTube", at(25, 9))
        assertTask("on friday at 5 pm call dad", "call dad", at(25, 17))
    }

    @Test
    fun bareHoursPickTheNextSensibleTime() {
        // 1–5 without AM/PM is afternoon for tasks, even if that means tomorrow.
        assertTask("call mum at 1", "call mum", at(25, 13))
        // Otherwise the next time the clock shows that hour: 11 PM tonight comes before 11 AM tomorrow.
        assertTask("call mum at 11", "call mum", at(24, 23))
        assertTask("call mum at 9 am", "call mum", at(25, 9))
        assertTask("call mum at 12", "call mum", at(25, 12))
        assertTask("open settings at noon", "open settings", at(25, 12))
    }

    @Test
    fun daysAndWeekdays() {
        assertTask("call mum tomorrow at 9", "call mum", at(25, 9))
        assertTask("tomorrow call mum at 5", "call mum", at(25, 17))
        assertTask("call dad on monday at 10", "call dad", at(28, 10))
        assertTask("call dad next thursday at 10 am", "call dad", ZonedDateTime.of(2026, 10, 1, 10, 0, 0, 0, zone))
        assertTask("call dad on thursday at 6 pm", "call dad", at(24, 18))
        assertTask("tonight at 9 turn on the flashlight", "turn on the flashlight", at(24, 21))
        assertTask("turn on the flashlight at 7 in the evening", "turn on the flashlight", at(24, 19))
        assertTask("open YouTube day after tomorrow at 8 pm", "open YouTube", at(26, 20))
    }

    @Test
    fun hoursAfterMidnightBelongToTheNightNamed() {
        assertScheduled("remind me tonight at 12 to lock up", "lock up", TaskKind.Reminder, at(25, 0))
        assertScheduled("remind me tomorrow night at 12 to lock up", "lock up", TaskKind.Reminder, at(26, 0))
        assertScheduled("kal raat 1 baje yaad dilana ki dawai leni hai", "dawai leni hai", TaskKind.Reminder, at(26, 1))
        assertScheduled("remind me on saturday night at 1 to call dad", "call dad", TaskKind.Reminder, at(27, 1))
        // Without a day, the next such hour.
        assertTask("raat 1 baje torch band karo", "torch band karo", at(25, 1))
    }

    @Test
    fun delays() {
        val request = parse("play despacito in 10 minutes") as TaskRequest.Scheduled
        assertEquals("play despacito", request.text)
        assertEquals(at(24, 14, 10), request.at)
        assertEquals(Duration.ofMinutes(10), request.delay)
        assertTask("in 10 minutes play despacito", "play despacito", at(24, 14, 10))
        assertTask("turn on the flashlight in half an hour", "turn on the flashlight", at(24, 14, 30))
        assertTask("turn off the torch in an hour and a half", "turn off the torch", at(24, 15, 30))
        assertTask("open YouTube after 2 hours", "open YouTube", at(24, 16))
        assertTask("open YouTube in 1 hour 15 minutes", "open YouTube", at(24, 15, 15))
        assertTask("turn off the flashlight 5 minutes later", "turn off the flashlight", at(24, 14, 5))
        assertTask("open camera in 90 seconds", "open camera", ZonedDateTime.of(2026, 9, 24, 14, 1, 30, 0, zone))
    }

    @Test
    fun alarms() {
        assertScheduled("ring me at 5", "", TaskKind.Alarm, at(24, 17))
        assertScheduled("wake me up at 6", "Wake up", TaskKind.Alarm, at(25, 6))
        assertScheduled("wake me up at 6:30 am tomorrow", "Wake up", TaskKind.Alarm, at(25, 6, 30))
        assertScheduled("at 6 am wake me up tomorrow", "Wake up", TaskKind.Alarm, at(25, 6))
        assertScheduled("set an alarm for 7", "", TaskKind.Alarm, at(24, 19))
        assertScheduled("set alarm for 6:30 am", "", TaskKind.Alarm, at(25, 6, 30))
        assertScheduled("set an alarm for 6 am for the gym", "the gym", TaskKind.Alarm, at(25, 6))
        assertScheduled("alarm at 5 am", "", TaskKind.Alarm, at(25, 5))
        assertScheduled("wake me up in 20 minutes", "Wake up", TaskKind.Alarm, at(24, 14, 20))
    }

    @Test
    fun timers() {
        assertScheduled("set a timer for 10 minutes", "Timer", TaskKind.Alarm, at(24, 14, 10))
        assertScheduled("timer 5 minutes", "Timer", TaskKind.Alarm, at(24, 14, 5))
        assertScheduled("start a timer for 1 hour", "Timer", TaskKind.Alarm, at(24, 15))
    }

    @Test
    fun reminders() {
        assertScheduled("remind me to drink water at 5", "drink water", TaskKind.Reminder, at(24, 17))
        assertScheduled("remind me at 5 to drink water", "drink water", TaskKind.Reminder, at(24, 17))
        assertScheduled("remind me in 10 minutes to check the oven", "check the oven", TaskKind.Reminder, at(24, 14, 10))
        assertScheduled("remind me tomorrow to pay rent", "pay rent", TaskKind.Reminder, at(25, 9))
        assertScheduled("remind me about the meeting at 3", "about the meeting", TaskKind.Reminder, at(24, 15))
        assertScheduled("remind me to call my mum tomorrow at 5", "call my mum", TaskKind.Reminder, at(25, 17))
        assertScheduled("remind me tomorrow to call mum at 5", "call mum", TaskKind.Reminder, at(25, 17))
        assertScheduled("remind me tonight to take my medicine", "take my medicine", TaskKind.Reminder, at(24, 20))
        assertScheduled("set a reminder for 6 pm to go for a walk", "go for a walk", TaskKind.Reminder, at(24, 18))
        assertScheduled("in 10 minutes remind me to stretch", "stretch", TaskKind.Reminder, at(24, 14, 10))
    }

    @Test
    fun hinglish() {
        assertScheduled("kal subah 7 baje utha dena", "Wake up", TaskKind.Alarm, at(25, 7))
        assertScheduled("mujhe kal subah 7 baje utha dena", "Wake up", TaskKind.Alarm, at(25, 7))
        assertScheduled("5 baje alarm laga do", "", TaskKind.Alarm, at(24, 17))
        assertScheduled("alarm laga do 5 baje", "", TaskKind.Alarm, at(24, 17))
        assertScheduled("10 minute ka timer laga do", "Timer", TaskKind.Alarm, at(24, 14, 10))
        assertScheduled(
            "mujhe 10 minute baad paani peene ke liye yaad dilana", "paani peene ke liye", TaskKind.Reminder, at(24, 14, 10),
        )
        assertScheduled("shaam 5 baje yaad dilana ki dawai leni hai", "dawai leni hai", TaskKind.Reminder, at(24, 17))
        assertTask("shaam 6 baje mummy ko call karna", "mummy ko call karna", at(24, 18))
        assertTask("raat 9 baje torch band karo", "torch band karo", at(24, 21))
        assertTask("saade 4 baje YouTube kholo", "YouTube kholo", at(24, 16, 30))
        assertTask("paune 5 baje YouTube kholo", "YouTube kholo", at(24, 16, 45))
        assertTask("dopahar 3 baje papa ko call karo", "papa ko call karo", at(24, 15))
        assertTask("aadhe ghante baad torch band karo", "torch band karo", at(24, 14, 30))
        assertTask("do ghante baad YouTube kholo", "YouTube kholo", at(24, 16))
    }

    @Test
    fun devanagari() {
        assertScheduled("कल सुबह 7 बजे उठा देना", "Wake up", TaskKind.Alarm, at(25, 7))
        assertTask("शाम 4 बजे मम्मी को फोन करना", "मम्मी को फोन करना", at(24, 16))
        assertTask("10 मिनट बाद टॉर्च बंद करो", "टॉर्च बंद करो", at(24, 14, 10))
        assertTask("१० मिनट बाद टॉर्च बंद करो", "टॉर्च बंद करो", at(24, 14, 10))
        assertScheduled("मुझे 5 बजे याद दिलाना कि पानी पीना है", "पानी पीना है", TaskKind.Reminder, at(24, 17))
    }

    @Test
    fun afterThisQueuesTheRequest() {
        assertEquals(TaskRequest.AfterCurrent("play this song"), parse("play this song after this"))
        assertEquals(TaskRequest.AfterCurrent("Play this song"), parse("Play this song after this."))
        assertEquals(TaskRequest.AfterCurrent("open WhatsApp"), parse("after this, open WhatsApp"))
        assertEquals(TaskRequest.AfterCurrent("open camera"), parse("when you're done open camera"))
        assertEquals(TaskRequest.AfterCurrent("turn off the flashlight"), parse("turn off the flashlight when you're done"))
        assertEquals(TaskRequest.AfterCurrent("open YouTube"), parse("then open YouTube"))
        assertEquals(TaskRequest.AfterCurrent("YouTube kholo"), parse("iske baad YouTube kholo"))
        assertEquals(TaskRequest.AfterCurrent("गाना चलाओ"), parse("इसके बाद गाना चलाओ"))
        assertEquals(TaskRequest.AfterCurrent("gaana chalao"), parse("gaana chalao uske baad"))
    }

    @Test
    fun afterThisWithATimeIsJustScheduled() {
        assertScheduled("after this, remind me at 5 to stretch", "stretch", TaskKind.Reminder, at(24, 17))
    }

    @Test
    fun phirSeMeansAgain() = assertNow("phir se call karo", "fir se YouTube kholo")

    @Test
    fun questionsAndPlainRequestsRunNow() = assertNow(
        "what's the weather at 5",
        "is it going to rain at 5 pm",
        "tell me a joke at 5",
        "do I have anything at 5",
        "can I leave at 5",
        "how long does it take to get there by 6",
        "call mum",
        "call 911",
        "play 21 savage",
        "open Chrome and search for cats",
        "turn on the flashlight",
        "open the 5 minute crafts app",
        "remind me to drink water",
        "set an alarm",
        "wake me up",
        "at 4",
        "call mum at",
        "",
    )

    @Test
    fun timesThatMayBelongToContentAreLeftToTheAgent() = assertNow(
        "text mum I'll be home at 6",
        "message mum that I'll be late at 8",
        "search for restaurants open at 10",
        "send \"see you at 5\" to Priya",
        "tell Priya the party starts at 7",
    )

    @Test
    fun repeatingRequestsAreLeftToTheAgent() = assertNow("wake me up every day at 7", "remind me daily at 9 to walk", "roz subah 6 baje utha dena")

    @Test
    fun forIsOnlyATimeForAlarmsAndReminders() = assertNow("look for 5 pm flights", "search for 7")

    @Test
    fun listingTasks() {
        listOf(
            "show my tasks", "what's scheduled", "what is scheduled", "what are my reminders", "my alarms", "tasks dikhao",
            "show me all my scheduled tasks", "what do I have scheduled", "any reminders", "list my tasks", "what's next",
            "Hey Wakey, show my tasks please",
        ).forEach { assertEquals("\"$it\"", TaskRequest.ListTasks, parse(it)) }
        assertNow("open tasks", "open my tasks app")
    }

    @Test
    fun cancelling() {
        assertEquals(TaskRequest.CancelScheduled(null, all = true, query = null), parse("cancel all tasks"))
        assertEquals(TaskRequest.CancelScheduled(null, all = true, query = null), parse("clear my scheduled tasks"))
        assertEquals(TaskRequest.CancelScheduled(TaskKind.Alarm, all = false, query = null), parse("cancel my alarm"))
        assertEquals(TaskRequest.CancelScheduled(TaskKind.Alarm, all = false, query = null), parse("turn off the alarm"))
        assertEquals(TaskRequest.CancelScheduled(TaskKind.Alarm, all = true, query = null), parse("delete all timers"))
        assertEquals(TaskRequest.CancelScheduled(TaskKind.Reminder, all = true, query = null), parse("delete all reminders"))
        assertEquals(TaskRequest.CancelScheduled(TaskKind.Reminder, all = false, query = "drink water"), parse("cancel the reminder to drink water"))
        assertEquals(TaskRequest.CancelScheduled(TaskKind.Alarm, all = true, query = null), parse("sab alarm cancel karo"))
        assertEquals(TaskRequest.CancelScheduled(TaskKind.Reminder, all = false, query = null), parse("reminder hata do"))
        val stopRinging = TaskRequest.CancelScheduled(TaskKind.Alarm, all = false, query = null, ringingOnly = true)
        assertEquals(stopRinging, parse("stop the alarm"))
        assertEquals(stopRinging, parse("Hey Wakey, stop the timer"))
        assertEquals(stopRinging, parse("stop ringing"))
        assertNow("cancel my Uber", "cancel the order", "stop the music")
    }

    @Test
    fun endTimesAreNotStartTimes() = assertNow("keep the flashlight on until 10", "play music till 6", "finish this by 5")
}
