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
