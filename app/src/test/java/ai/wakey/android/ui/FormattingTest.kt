package ai.wakey.android.ui

import ai.wakey.android.config.TtsEngine
import ai.wakey.android.core.InputSource
import ai.wakey.android.core.TurnTimings
import ai.wakey.android.tasks.TaskKind
import ai.wakey.android.tasks.TaskStatus
import ai.wakey.android.tasks.WakeyTask
import ai.wakey.android.tts.VoiceOption
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattingTest {
    @Test
    fun durationsUseMillisecondsBelowOneSecond() {
        assertEquals("0 ms", formatDuration(0))
        assertEquals("180 ms", formatDuration(180))
        assertEquals("999 ms", formatDuration(999))
        assertEquals("0 ms", formatDuration(-5))
    }

    @Test
    fun durationsRoundToTenthsThenWholeSeconds() {
        assertEquals("1.0 s", formatDuration(1_000))
        assertEquals("1.2 s", formatDuration(1_234))
        assertEquals("1.3 s", formatDuration(1_250))
        assertEquals("9.9 s", formatDuration(9_949))
        assertEquals("10 s", formatDuration(9_950))
        assertEquals("14 s", formatDuration(14_400))
    }

    @Test
    fun countsAbbreviateThousands() {
        assertEquals("850", formatCount(850))
        assertEquals("999", formatCount(999))
        assertEquals("1k", formatCount(1_000))
        assertEquals("1.4k", formatCount(1_400))
        assertEquals("1.5k", formatCount(1_450))
        assertEquals("10k", formatCount(9_950))
        assertEquals("14k", formatCount(14_200))
    }

    @Test
    fun fullVoiceTurnSummary() {
        val timings = TurnTimings(
            source = InputSource.WakeWord,
            wakeDetectionMs = 180,
            sttConnectMs = 420,
            transcriptionMs = 310,
            speechSessionMs = 2_600,
            firstActionMs = 1_200,
            spokenReplyMs = 2_100,
            totalMs = 4_800,
            route = "agent",
            steps = 3,
            llmCalls = 2,
            promptTokens = 1_200,
            completionTokens = 200,
        )
        assertEquals(
            "wake 180 ms · STT connect 420 ms · transcript 310 ms · first action 1.2 s · voice 2.1 s · 3 steps · 2 LLM calls · 1.4k tokens",
            formatTimingSummary(timings),
        )
    }

    @Test
    fun summaryShowsOnlyMeasuredFields() {
        assertEquals("first action 40 ms", formatTimingSummary(TurnTimings(InputSource.Text, firstActionMs = 40, route = "fast")))
        assertEquals("", formatTimingSummary(TurnTimings(InputSource.Text)))
    }

    @Test
    fun summaryUsesSingularForOne() {
        val timings = TurnTimings(InputSource.Mic, steps = 1, llmCalls = 1, promptTokens = 1)
        assertEquals("1 step · 1 LLM call · 1 token", formatTimingSummary(timings))
    }

    @Test
    fun detailsListEveryFieldWithDashesForUnmeasured() {
        val details = timingDetails(TurnTimings(InputSource.PushToTalk, totalMs = 3_400, promptTokens = 900, completionTokens = 40)).toMap()
        assertEquals("push-to-talk", details["Input"])
        assertEquals("—", details["Route"])
        assertEquals("—", details["Wake detection"])
        assertEquals("3.4 s", details["Total"])
        assertEquals("900 + 40", details["Tokens (prompt + completion)"])
    }

    @Test
    fun speechRateHasOneDecimal() {
        assertEquals("1.0×", formatSpeechRate(1f))
        assertEquals("0.5×", formatSpeechRate(0.5f))
        assertEquals("2.0×", formatSpeechRate(2f))
        assertEquals("1.3×", formatSpeechRate(1.3f))
    }

    @Test
    fun indianEnglishVoicesComeFirstInOriginalOrder() {
        fun voice(id: String, tag: String) = VoiceOption(id, id, tag, offline = false, engine = TtsEngine.Deepgram)
        val voices = listOf(voice("thalia", "en-US"), voice("meena", "en-IN"), voice("draco", "en-GB"), voice("priya", "en-IN"), voice("naveen", "en-in"))
        assertEquals(listOf("meena", "priya", "naveen", "thalia", "draco"), indianEnglishFirst(voices).map { it.id })
    }

    @Test
    fun failureSummariesAreRecognised() {
        assertTrue(isFailureSummary("Failed: HTTP 401"))
        assertTrue(isFailureSummary("Not available"))
        assertFalse(isFailureSummary("OK — model replied in 420 ms"))
    }

    @Test
    fun wakePhrasesNormaliseLikeSettings() {
        assertEquals("Hey Wakey", normalizeWakePhrase("  Hey   Wakey "))
    }

    @Test
    fun taskStatusLines() {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 9, 24, 14, 0, 0, 0, zone)
        fun ms(hour: Int, minute: Int = 0, day: Int = 24) = ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
        fun task(status: TaskStatus, due: Long? = null, finished: Long? = null, result: String? = null) =
            WakeyTask(1, "call mum", TaskKind.Task, status, createdAtMs = ms(13), dueAtMs = due, finishedAtMs = finished, result = result)

        assertEquals("4:00 PM · in 2 h", taskStatusLine(task(TaskStatus.Scheduled, due = ms(16)), now))
        assertEquals("2:12 PM · in 12 min", taskStatusLine(task(TaskStatus.Scheduled, due = ms(14, 12)), now))
        assertEquals("Tomorrow 9:00 AM", taskStatusLine(task(TaskStatus.Scheduled, due = ms(9, day = 25)), now))
        assertEquals("After the current task", taskStatusLine(task(TaskStatus.Queued), now))
        assertEquals("Due now · up next", taskStatusLine(task(TaskStatus.Queued, due = ms(14)), now))
        assertEquals("Due 1:55 PM · unlock to run", taskStatusLine(task(TaskStatus.WaitingForUnlock, due = ms(13, 55)), now))
        assertEquals("Running now", taskStatusLine(task(TaskStatus.Running), now))
        assertEquals("1:58 PM · Called mum.", taskStatusLine(task(TaskStatus.Done, finished = ms(13, 58), result = "Called mum.\nMore"), now))
        assertEquals("1:58 PM · Cancelled", taskStatusLine(task(TaskStatus.Cancelled, finished = ms(13, 58)), now))
    }

    @Test
    fun floatingButtonNoteAsksForScreenControlWhenNeeded() {
        assertEquals("Turn on Wakey screen control to show it.", floatingButtonNote(enabled = true, screenControl = false))
        assertTrue(floatingButtonNote(enabled = false, screenControl = false).startsWith("Tap it in any app"))
    }
}
