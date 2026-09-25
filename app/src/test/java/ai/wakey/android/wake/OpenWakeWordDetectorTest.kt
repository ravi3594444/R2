package ai.wakey.android.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWakeWordDetectorTest {
    /** Mel rows hold the input's last sample, embeddings the newest mel row, scores come from a script. */
    private class FakeModels(private val scores: List<Float> = emptyList()) : OwwModels {
        val melInputs = mutableListOf<FloatArray>()
        var scored = 0

        override fun melspectrogram(samples: FloatArray, length: Int): FloatArray {
            if (length != OwwPipeline.NOISE_SAMPLES) melInputs += samples.copyOf(length)
            val rows = (length - OwwPipeline.CONTEXT) / 160
            return FloatArray(rows * OwwPipeline.MEL_BINS) { samples[length - 1] }
        }

        override fun embedding(window: FloatArray): FloatArray = FloatArray(OwwPipeline.EMBEDDING) { window.last() }

        override fun score(features: FloatArray): Float = scores.getOrElse(scored++) { features.last() / 1_000f }

        override fun close() = Unit
    }

    private fun ramp(from: Int, count: Int) = ShortArray(count) { (from + it).toShort() }

    @Test
    fun scoresEvery80msWhateverTheBlockSize() {
        val models = FakeModels()
        val pipeline = OwwPipeline(models)
        var scores = 0
        // 1000-sample blocks: steps still fall on exact 1280-sample boundaries.
        repeat(32) { pipeline.accept(ramp(it * 1_000, 1_000), 1_000) { scores++ } }
        assertEquals(32_000 / OwwPipeline.STEP, scores)
        assertEquals(1_279f, models.melInputs[0].last())
        assertEquals(2_559f, models.melInputs[1].last())
    }

    @Test
    fun theFirstStepSeesOneStepAndLaterOnesAddContext() {
        val models = FakeModels()
        val pipeline = OwwPipeline(models)
        repeat(3) { pipeline.accept(ramp(it * OwwPipeline.STEP, OwwPipeline.STEP), OwwPipeline.STEP) {} }
        assertEquals(listOf(1_280, 1_760, 1_760), models.melInputs.map { it.size })
        // The last 1760 samples, oldest first: 480 of context, then the new step.
        assertEquals(2_560f - 480, models.melInputs[2].first())
        assertEquals(3_839f, models.melInputs[2].last())
    }

    @Test
    fun theFirstFiveScoresAfterAResetAreZero() {
        val pipeline = OwwPipeline(FakeModels(List(20) { 0.9f }))
        val scores = mutableListOf<Float>()
        repeat(8) { pipeline.accept(ShortArray(OwwPipeline.STEP), OwwPipeline.STEP) { scores += it } }
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0.9f, 0.9f, 0.9f), scores)
        pipeline.reset()
        scores.clear()
        repeat(6) { pipeline.accept(ShortArray(OwwPipeline.STEP), OwwPipeline.STEP) { scores += it } }
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0.9f), scores)
    }

    @Test
    fun aResetStartsAgainFromOneStep() {
        val models = FakeModels()
        val pipeline = OwwPipeline(models)
        repeat(3) { pipeline.accept(ShortArray(OwwPipeline.STEP), OwwPipeline.STEP) {} }
        pipeline.reset()
        pipeline.accept(ShortArray(OwwPipeline.STEP / 2), OwwPipeline.STEP / 2) {}
        pipeline.reset()
        pipeline.accept(ShortArray(OwwPipeline.STEP), OwwPipeline.STEP) {}
        // Half a step before the second reset was dropped with it.
        assertEquals(listOf(1_280, 1_760, 1_760, 1_280), models.melInputs.map { it.size })
    }

    @Test
    fun aConfidentScoreIsASureDetection() {
        val detector = detector(List(5) { 0f } + listOf(0.1f, 0.8f))
        val found = feed(detector, 7)
        assertEquals(1, found.size)
        assertFalse(found.single().needsCheck)
        assertEquals("HEY JARVIS", found.single().phrase)
    }

    @Test
    fun aBorderlineScoreIsCheckedOnceItPeaks() {
        val detector = detector(List(5) { 0f } + listOf(0.32f, 0.41f, 0.36f, 0.1f))
        val found = feed(detector, 9)
        assertEquals(1, found.size)
        assertTrue(found.single().needsCheck)
    }

    @Test
    fun quietScoresAreIgnored() {
        val detector = detector(List(5) { 0f } + listOf(0.1f, 0.25f, 0.2f))
        assertTrue(feed(detector, 8).isEmpty())
    }

    @Test
    fun sensitivityMovesTheThreshold() {
        assertEquals(0.5f, OpenWakeWordDetector.thresholdFor(0.5f), 1e-6f)
        assertEquals(0.3f, OpenWakeWordDetector.thresholdFor(1f), 1e-6f)
        assertEquals(0.7f, OpenWakeWordDetector.thresholdFor(0f), 1e-6f)
        assertEquals(0.4f, OpenWakeWordDetector.checkThresholdFor(0.5f), 1e-6f)
        // At the most eager setting every score from 0.3 up is a sure detection.
        val eager = detector(List(5) { 0f } + listOf(0.31f), sensitivity = 1f)
        assertFalse(feed(eager, 6).single().needsCheck)
    }

    @Test
    fun noDetectionWithoutAudio() {
        val detector = detector(listOf(0.99f))
        assertNull(detector.accept(ShortArray(100), 100))
    }

    private fun detector(scores: List<Float>, sensitivity: Float = 0.5f) =
        OpenWakeWordDetector(OwwPipeline(FakeModels(scores))).apply { setKeyword(OpenWakeWordDetector.KEYWORD, sensitivity) }

    private fun feed(detector: OpenWakeWordDetector, steps: Int): List<WakeDetection> =
        (0 until steps).mapNotNull { detector.accept(ShortArray(OwwPipeline.STEP), OwwPipeline.STEP) }
}
