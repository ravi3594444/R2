package ai.wakey.android.core

import ai.wakey.android.core.WakeTranscript.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeTranscriptTest {
    private fun check(text: String, isFinal: Boolean = false, phrase: String = "Hey Wakey") =
        WakeTranscript.check(text, phrase, isFinal)

    @Test
    fun `the wake name, spelled or misheard, confirms a check`() {
        for (text in listOf(
            "Hey Wakey", "Hey, Wakey, turn on the torch", "hey waky open youtube", "Hey Vicky.", "Hey Becky what time",
            "Hey wiki", "hey Ricky open camera", "Wakey", "Heywakey open settings", "हे वेकी टॉर्च जलाओ", "okay hey wakey",
        )) {
            assertEquals(text, Verdict.Heard, check(text))
        }
    }

    @Test
    fun `other speech is dropped once there are enough words to tell`() {
        for (text in listOf("hey what are you doing", "they make a lot of noise", "hey wait for me please", "open the door now")) {
            assertEquals(text, Verdict.NotHeard, check(text))
        }
        assertEquals(Verdict.NotHeard, check("hey", isFinal = true))
        assertEquals(Verdict.NotHeard, check("", isFinal = true))
    }

    @Test
    fun `a short interim transcript waits for more words`() {
        assertEquals(Verdict.Undecided, check(""))
        assertEquals(Verdict.Undecided, check("Hey"))
        assertEquals(Verdict.Undecided, check("hey what is"))
    }

    @Test
    fun `hey alone or a near word does not count`() {
        assertEquals(Verdict.NotHeard, check("hey make a call", isFinal = true))
        assertEquals(Verdict.NotHeard, check("hey hey hey", isFinal = true))
    }

    @Test
    fun `the wake name must come at the start`() {
        assertEquals(Verdict.NotHeard, check("so I told him wakey wakey", isFinal = true))
    }

    @Test
    fun `custom phrases use their longest word`() {
        assertEquals(Verdict.Heard, check("hello computer lights on", phrase = "Hello Computer"))
        assertEquals(Verdict.Heard, check("hello commuter", phrase = "Hello Computer"))
        assertEquals(Verdict.NotHeard, check("hello there how are you", phrase = "Hello Computer"))
    }

    @Test
    fun `strip still removes misheard wake phrases`() {
        assertEquals("open camera", WakeTranscript.strip("Hey Ricky, open camera", "Hey Wakey"))
        assertEquals("turn on the torch", WakeTranscript.strip("Hey Wakey turn on the torch", "Hey Wakey"))
    }
}
