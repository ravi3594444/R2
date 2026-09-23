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
            .toSherpaLine(scoring.boost, scoring.threshold)
        assertEquals("▁HE LL O ▁COMP U TER :1.25 #0.24 @HELLO_COMPUTER", line)
    }

    private fun assertScoring(boost: Float, threshold: Float, actual: KeywordScoring) {
        assertEquals(boost, actual.boost, 1e-6f)
        assertEquals(threshold, actual.threshold, 1e-6f)
    }
}
