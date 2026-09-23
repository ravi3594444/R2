package ai.wakey.android.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    private fun sentences(text: String) = SpeechText.split(text, maxChars = 2_000)

    @Test
    fun detectsDevanagariOnly() {
        assertTrue(SpeechText.hasDevanagari("ठीक है, calculator खोल रहा हूँ"))
        assertTrue(SpeechText.hasDevanagari("Done।"))
        assertFalse(SpeechText.hasDevanagari("Theek hai, calculator khol raha hoon."))
        assertFalse(SpeechText.hasDevanagari("Café — naïve ✓ 😀"))
    }

    @Test
    fun splitsSentencesAndKeepsPunctuation() {
        assertEquals(
            listOf("Flashlight is on.", "Anything else?", "Okay!"),
            sentences("Flashlight is on. Anything else? Okay!"),
        )
    }

    @Test
    fun keepsAbbreviationsInitialsAndDecimalsTogether() {
        assertEquals(
            listOf("Dr. Rao will call at 5 p.m. tomorrow.", "J. K. Rowling wrote it."),
            sentences("Dr. Rao will call at 5 p.m. tomorrow. J. K. Rowling wrote it."),
        )
        assertEquals(listOf("It costs 3.5 rupees, e.g. on google.com today."), sentences("It costs 3.5 rupees, e.g. on google.com today."))
    }

    @Test
    fun splitsDevanagariDandaAndClosingQuotes() {
        assertEquals(listOf("ठीक है।", "कैलकुलेटर खोल रहा हूँ।"), sentences("ठीक है। कैलकुलेटर खोल रहा हूँ।"))
        assertEquals(listOf("He said \"stop.\"", "Then left."), sentences("He said \"stop.\" Then left."))
        assertEquals(listOf("Wait...", "okay."), sentences("Wait... okay."))
    }

    @Test
    fun normalisesWhitespaceAndDropsBlankInput() {
        assertEquals(listOf("Theek hai, calculator khol raha hoon."), sentences("  Theek   hai,\tcalculator khol raha hoon.  "))
        assertEquals(emptyList<String>(), sentences(" \n\t "))
    }

    @Test
    fun linesAreBoundaries() {
        assertEquals(listOf("Steps", "Open Settings", "Tap Bluetooth"), sentences("Steps\nOpen Settings\r\n\nTap Bluetooth"))
    }

    @Test
    fun shortSentencesRideAlongWithTheNext() {
        val chunks = SpeechText.split("Okay. Opening YouTube now. Anything else?", maxChars = 2_000, minChars = 24)
        assertEquals(listOf("Okay. Opening YouTube now.", "Anything else?"), chunks)
    }

    @Test
    fun greedyPackingKeepsLineBreaksAndLimit() {
        val text = "One. Two.\nThree. Four is longer than the rest."
        assertEquals(listOf("One. Two.\nThree. Four is longer than the rest."), SpeechText.split(text, 100, minChars = Int.MAX_VALUE))
        val packed = SpeechText.split(text, 20, minChars = Int.MAX_VALUE)
        assertEquals(listOf("One. Two.\nThree.", "Four is longer than", "the rest."), packed)
    }

    @Test
    fun longSentencesBreakAtClausesThenSpacesWithinLimit() {
        val text = "Bluetooth is off, so I opened Settings and tapped Connected devices for you just now"
        val pieces = SpeechText.split(text, maxChars = 30)
        assertTrue(pieces.toString(), pieces.all { it.length <= 30 })
        assertEquals("Bluetooth is off,", pieces.first())
        assertEquals(text.split(' '), pieces.joinToString(" ").split(' '))
    }

    @Test
    fun hardCutNeverSplitsASurrogatePair() {
        val text = "a".repeat(9) + "😀" + "b".repeat(9)
        val pieces = SpeechText.split(text, maxChars = 10)
        assertTrue(pieces.toString(), pieces.all { it.length <= 10 })
        assertEquals(text, pieces.joinToString(""))
        pieces.forEach { assertFalse(it.last().isHighSurrogate()) }
    }
}
