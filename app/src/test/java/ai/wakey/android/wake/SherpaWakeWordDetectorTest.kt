package ai.wakey.android.wake

import org.junit.Assert.assertEquals
import org.junit.Test

/** The sensitivity mapping validated on desktop (the native detector itself can't run on the JVM). */
class SherpaWakeWordDetectorTest {
    @Test
    fun `maps sensitivity to boost and threshold`() {
        assertScoring(1.0f, 0.30f, SherpaWakeWordDetector.scoringFor(0f))
        assertScoring(1.5f, 0.18f, SherpaWakeWordDetector.scoringFor(0.5f))
        assertScoring(2.0f, 0.06f, SherpaWakeWordDetector.scoringFor(1f))
    }

    @Test
    fun `clamps out-of-range sensitivity`() {
        assertEquals(SherpaWakeWordDetector.scoringFor(0f), SherpaWakeWordDetector.scoringFor(-3f))
        assertEquals(SherpaWakeWordDetector.scoringFor(1f), SherpaWakeWordDetector.scoringFor(7f))
        assertEquals(SherpaWakeWordDetector.scoringFor(0.5f), SherpaWakeWordDetector.scoringFor(Float.NaN))
    }

    @Test
    fun `formats keyword lines with two decimals`() {
        val scoring = SherpaWakeWordDetector.scoringFor(0.25f)
        val line = EncodedKeyword("HELLO COMPUTER", listOf("▁HE", "LL", "O", "▁COMP", "U", "TER"))
            .toSherpaKeywords(scoring.boost, scoring.threshold)
        assertEquals("▁HE LL O ▁COMP U TER :1.25 #0.24 @HELLO_COMPUTER", line)
    }

    @Test
    fun `pronunciation variants share the phrase's scoring and tag`() {
        val keyword = EncodedKeyword(
            "HEY WAKEY", listOf("▁HE", "Y", "▁WA", "KE", "Y"),
            variants = listOf(listOf("▁HE", "Y", "▁WA", "K", "Y"), listOf("▁HE", "Y", "▁WA", "KE", "E")),
        )
        assertEquals(
            "▁HE Y ▁WA KE Y :1.50 #0.18 @HEY_WAKEY/▁HE Y ▁WA K Y :1.50 #0.18 @HEY_WAKEY/▁HE Y ▁WA KE E :1.50 #0.18 @HEY_WAKEY",
            keyword.toSherpaKeywords(1.5f, 0.18f),
        )
    }

    @Test
    fun `a detection reports the user's phrase whichever variant fired`() {
        val keyword = EncodedKeyword("WHAT'S UP WAKEY", listOf("▁WHAT", "'", "S", "▁UP", "▁WA", "KE", "Y"))
        assertEquals("WHAT'S UP WAKEY", SherpaWakeWordDetector.reportedPhrase("WHAT'S_UP_WAKEY", keyword))
        // A tag from another list (none today) is still readable.
        assertEquals("HELLO COMPUTER", SherpaWakeWordDetector.reportedPhrase("HELLO_COMPUTER", keyword))
        assertEquals("HEY WAKEY", SherpaWakeWordDetector.reportedPhrase("HEY_WAKEY", null))
    }

    private fun assertScoring(boost: Float, threshold: Float, actual: KeywordScoring) {
        assertEquals(boost, actual.boost, 1e-6f)
        assertEquals(threshold, actual.threshold, 1e-6f)
    }
}
