package ai.wakey.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantControllerTextTest {
    private fun strip(text: String, phrase: String = "Hey Wakey") = AssistantController.stripWakePhrase(text, phrase)

    @Test
    fun stripsFullWakePhraseAtStart() {
        assertEquals("turn on the flashlight.", strip("Hey Wakey, turn on the flashlight."))
        assertEquals("open Calculator", strip("hey wakey open Calculator"))
    }

    @Test
    fun stripsPartialOrMisheardWakePhrase() {
        assertEquals("turn on the flashlight", strip("Wakey, turn on the flashlight"))
        assertEquals("open YouTube", strip("Hey Wacky, open YouTube"))
        assertEquals("open YouTube", strip("Hey Wakie open YouTube"))
    }

    @Test
    fun stripsWakeyAsFluxMishearsIt() {
        assertEquals("Turn on the flashlight.", strip("Hey, Becky. Turn on the flashlight."))
        assertEquals("open YouTube", strip("Hey Vicky open YouTube"))
        assertEquals("", strip("Hey, Becky."))
        assertEquals("Vicky is my friend", strip("Vicky is my friend", "Hello Computer"))
    }

    @Test
    fun aHeadStartIsKeptOnlyForTheSameWords() {
        assertTrue(AssistantController.sameWords("Open YouTube and search lo-fi.", "open youtube and search lo fi"))
        assertTrue(AssistantController.sameWords("यूट्यूब खोलो।", "यूट्यूब खोलो"))
        assertFalse(AssistantController.sameWords("Set an alarm.", "Set an alarm for six thirty."))
        assertFalse(AssistantController.sameWords("", "open YouTube"))
    }

    @Test
    fun leavesCommandsWithoutWakePhraseAlone() {
        assertEquals("turn on the flashlight", strip("turn on the flashlight"))
        assertEquals("open Settings and find Bluetooth", strip("open Settings and find Bluetooth"))
        assertEquals("", strip(""))
    }

    @Test
    fun onlyWakePhraseLeavesNothing() {
        assertEquals("", strip("Hey Wakey."))
    }

    @Test
    fun customPhrase() {
        assertEquals("what's the time", strip("Hello computer, what's the time", "Hello Computer"))
    }

    @Test
    fun stopPhrases() {
        assertTrue(AssistantController.isStopPhrase("Stop."))
        assertTrue(AssistantController.isStopPhrase("ruko"))
        assertTrue(AssistantController.isStopPhrase("बस"))
        assertFalse(AssistantController.isStopPhrase("stop the music in Spotify"))
    }

    @Test
    fun languageTagFollowsScript() {
        assertEquals("hi-IN", AssistantController.languageTagFor("टॉर्च चालू है", listOf("hi")))
        assertEquals("en-IN", AssistantController.languageTagFor("Torch chalu ho gaya", listOf("hi")))
        assertNull(AssistantController.languageTagFor("Flashlight is on.", listOf("en")))
    }
}

/** The one-breath voice path after transcription: strip the wake phrase, then route directly. */
class WakeTranscriptRoutingTest {
    private fun route(transcript: String) =
        ai.wakey.android.agent.FastCommandRouter.route(AssistantController.stripWakePhrase(transcript, "Hey Wakey"))

    @Test
    fun liveFluxTranscriptsReachFastCommands() {
        // Transcripts observed from Flux on the desktop pipeline tests.
        assertEquals(ai.wakey.android.agent.FastCommand.Torch(true), route("Hey, Wakey. Turn on the flashlight."))
        assertEquals(ai.wakey.android.agent.FastCommand.Torch(true), route("HEY WAKY TURN ON THE FLASHLIGHT"))
        assertEquals(ai.wakey.android.agent.FastCommand.OpenApp("Calculator"), route("Hey Wakey, open Calculator."))
    }

    @Test
    fun multiStepRequestsGoToTheAgent() {
        assertNull(route("Hey Wakey, open Settings and find Bluetooth."))
    }

    @Test
    fun punctuatedWakePhraseIsNormalised() {
        assertEquals("Hey Wakey", ai.wakey.android.config.WakeySettings.normalizeWakePhrase(" Hey, Wakey! "))
    }
}
