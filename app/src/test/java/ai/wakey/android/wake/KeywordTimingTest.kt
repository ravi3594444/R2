package ai.wakey.android.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cases recorded from sherpa-onnx 1.13.8 (Python, same model and 20 ms feeding as the app) on the
 * Deepgram TTS test clips, with and without 5.12 s of leading silence. The silence makes sherpa
 * reset its decoder internally, so the raw timestamps shift by only 0.32 s while the audio moved
 * by 5.12 s; the located span must move by exactly 5.12 s.
 */
class KeywordTimingTest {
    @Test
    fun `places a detection reported in a later chunk than its last token`() {
        // "Hey Wakey." (aura-2-thalia): last token at frame 31 = 7th of its chunk, fires two frames later.
        val span = KeywordTiming.locate(floatArrayOf(0.64f, 0.84f, 1.04f, 1.16f, 1.24f), decodedChunks = 5, trailingBlanks = 1)
        assertEquals(KeywordSpan(seconds(0.64), seconds(1.28)), span)
    }

    @Test
    fun `survives sherpa's silent decoder reset`() {
        val span = KeywordTiming.locate(floatArrayOf(0.96f, 1.16f, 1.36f, 1.48f, 1.56f), decodedChunks = 21, trailingBlanks = 1)
        assertEquals(KeywordSpan(seconds(0.64 + 5.12), seconds(1.28 + 5.12)), span)
    }

    @Test
    fun `places a detection reported in the same chunk as its last token`() {
        // "Hey Wakey, turn on the flashlight." (aura-2-arcas): last token 5th of its chunk.
        val span = KeywordTiming.locate(floatArrayOf(1.0f, 1.08f, 1.2f, 1.36f, 1.48f), decodedChunks = 5, trailingBlanks = 1)
        assertEquals(KeywordSpan(seconds(1.00), seconds(1.52)), span)
        val shifted = KeywordTiming.locate(floatArrayOf(1.32f, 1.4f, 1.52f, 1.68f, 1.8f), decodedChunks = 21, trailingBlanks = 1)
        assertEquals(KeywordSpan(seconds(1.00 + 5.12), seconds(1.52 + 5.12)), shifted)
    }

    @Test
    fun `accounts for more trailing blanks`() {
        // With 2 trailing blanks a last token at in-chunk frame 5 fires in the next chunk.
        val span = KeywordTiming.locate(floatArrayOf(1.0f, 1.48f), decodedChunks = 6, trailingBlanks = 2)
        assertEquals(KeywordSpan(seconds(1.00), seconds(1.52)), span)
    }

    @Test
    fun `rejects missing or impossible timestamps`() {
        assertNull(KeywordTiming.locate(FloatArray(0), decodedChunks = 5, trailingBlanks = 1))
        assertNull(KeywordTiming.locate(floatArrayOf(0.2f, 1.24f), decodedChunks = 1, trailingBlanks = 1))
    }

    private fun seconds(s: Double) = Math.round(s * 16_000)
}
