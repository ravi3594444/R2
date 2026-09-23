package ai.wakey.android.service

import ai.wakey.android.core.AgentActionInfo
import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.core.ChatEntry
import ai.wakey.android.core.PendingConfirmation
import ai.wakey.android.core.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    private fun content(state: AssistantUiState, phrase: String = "Hey Wakey") = listeningContent(state, phrase)

    private fun entry(speaker: Speaker, text: String) = ChatEntry(id = text.length.toLong(), speaker = speaker, text = text, timestampMs = 0)

    private fun action(description: String, result: String? = null, step: Int = 1, maxSteps: Int = 12) =
        AgentActionInfo(step, maxSteps, description, toolName = "tap", result = result, success = result?.let { true })

    @Test
    fun wakeListeningNamesThePhraseAndShowsStatus() {
        val c = content(AssistantUiState(phase = AssistantPhase.WakeListening, statusMessage = "Say “Hey Jarvis” followed by your request."), "Hey Jarvis")
        assertEquals("Listening for “Hey Jarvis”", c.title)
        assertEquals("Say “Hey Jarvis” followed by your request.", c.text)
    }

    @Test
    fun idleReadsAsListeningAndHasNoTextWithoutStatus() {
        val c = content(AssistantUiState(phase = AssistantPhase.Idle))
        assertEquals("Listening for “Hey Wakey”", c.title)
        assertNull(c.text)
    }

    @Test
    fun blankWakePhraseFallsBackToGenericTitle() {
        assertEquals("Listening for the wake word", content(AssistantUiState(phase = AssistantPhase.WakeListening), "  ").title)
    }

    @Test
    fun hearingShowsLiveTranscriptThenFallsBackToStatus() {
        val hearing = AssistantUiState(phase = AssistantPhase.Hearing, liveTranscript = " turn on the ", statusMessage = "old")
        assertEquals("Hearing you…", content(hearing).title)
        assertEquals("turn on the", content(hearing).text)
        assertEquals("old", content(hearing.copy(liveTranscript = "  ")).text)
    }

    @Test
    fun transcriptOnlyShowsWhileHearing() {
        val c = content(AssistantUiState(phase = AssistantPhase.WakeListening, liveTranscript = "stale words"))
        assertNull(c.text)
    }

    @Test
    fun hearingAnAnswerShowsTheAnswerOverThePendingQuestion() {
        val pending = PendingConfirmation(7, "Send the message to Mom?", "“On my way”")
        val state = AssistantUiState(phase = AssistantPhase.Hearing, pendingConfirmation = pending)
        assertEquals("Send the message to Mom?", content(state).text)
        assertEquals("yes", content(state.copy(liveTranscript = "yes")).text)
    }

    @Test
    fun pendingQuestionOutranksActionDetail() {
        val state = AssistantUiState(
            phase = AssistantPhase.Acting,
            currentAction = action("Tapping “Send”", step = 3),
            pendingConfirmation = PendingConfirmation(7, "Send the message to Mom?", "“On my way”"),
        )
        assertEquals("Send the message to Mom?", content(state).text)
    }

    @Test
    fun thinkingShowsLastResultElseDescriptionElseTheRequest() {
        val request = listOf(entry(Speaker.User, "open Settings and find Bluetooth"))
        val base = AssistantUiState(phase = AssistantPhase.Thinking, entries = request)
        assertEquals("Thinking…", content(base).title)
        assertEquals("open Settings and find Bluetooth", content(base).text)
        assertEquals("Opening Settings", content(base.copy(currentAction = action("Opening Settings"))).text)
        assertEquals("Opened Settings", content(base.copy(currentAction = action("Opening Settings", "Opened Settings"))).text)
    }

    @Test
    fun actingTitleNamesTheActionAndTextShowsProgressOrResult() {
        val running = AssistantUiState(phase = AssistantPhase.Acting, currentAction = action(" Tapping “Bluetooth” ", step = 2, maxSteps = 12))
        assertEquals("Acting: Tapping “Bluetooth”", content(running).title)
        assertEquals("Step 2 of 12", content(running).text)
        val done = running.copy(currentAction = action("Tapping “Bluetooth”", "Bluetooth settings are open"))
        assertEquals("Bluetooth settings are open", content(done).text)
    }

    @Test
    fun singleStepFastCommandHasNoProgressText() {
        val state = AssistantUiState(phase = AssistantPhase.Acting, currentAction = action("Turning the flashlight on", step = 1, maxSteps = 1))
        assertEquals("Acting: Turning the flashlight on", content(state).title)
        assertNull(content(state).text)
    }

    @Test
    fun actingWithoutAnActionUsesPlainTitle() {
        assertEquals("Acting…", content(AssistantUiState(phase = AssistantPhase.Acting)).title)
        assertEquals("Acting…", content(AssistantUiState(phase = AssistantPhase.Acting, currentAction = action(" "))).title)
    }

    @Test
    fun speakingShowsTheReplyButNotTheUsersWords() {
        val replied = AssistantUiState(
            phase = AssistantPhase.Speaking,
            entries = listOf(entry(Speaker.User, "open YouTube"), entry(Speaker.Wakey, "Opened YouTube.")),
        )
        assertEquals("Speaking…", content(replied).title)
        assertEquals("Opened YouTube.", content(replied).text)
        assertNull(content(replied.copy(entries = replied.entries.dropLast(1))).text)
    }

    @Test
    fun publicTitleNeverCarriesSpeechOrAppNames() {
        val acting = AssistantUiState(phase = AssistantPhase.Acting, currentAction = action("Opening WhatsApp"), liveTranscript = "message Mom")
        assertEquals("Acting…", content(acting).publicTitle)
        assertFalse(content(acting.copy(phase = AssistantPhase.Hearing)).publicTitle.contains("Mom"))
    }

    @Test
    fun longTitleAndTextAreClipped() {
        val long = "word ".repeat(200)
        val c = content(AssistantUiState(phase = AssistantPhase.Acting, currentAction = action(long, result = long)))
        assertEquals(MAX_TITLE_CHARS, c.title.length)
        assertTrue(c.title.endsWith("…"))
        assertTrue(c.text!!.length <= MAX_TEXT_CHARS)
        assertTrue(c.text!!.endsWith("…"))
    }

    @Test
    fun clipLeavesShortTextAlone() {
        assertEquals("hello", clip("hello", 5))
    }

    @Test
    fun clipAddsEllipsisWithinLimit() {
        assertEquals("abcd…", clip("abcdefgh", 5))
        assertEquals("ab…", clip("ab   cdefgh", 5))
    }

    @Test
    fun clipNeverSplitsASurrogatePair() {
        val text = "abc😀defg" // "abc😀defg"
        val clipped = clip(text, 5)
        assertEquals("abc…", clipped)
        assertFalse(clipped.any { Character.isSurrogate(it) })
    }
}
