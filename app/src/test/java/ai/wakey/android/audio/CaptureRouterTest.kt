package ai.wakey.android.audio

import ai.wakey.android.audio.CaptureRouter.Mode
import ai.wakey.android.wake.EncodedKeyword
import ai.wakey.android.wake.WakeDetection
import ai.wakey.android.wake.WakeWordDetector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRouterTest {
    private var now = 10_000L
    private val warnings = mutableListOf<String>()
    private val router = CaptureRouter({ now }) { message, _ -> warnings += message }
    private val detector = FakeDetector()
    private val events = mutableListOf<WakeEvent>()
    private val chunks = mutableListOf<ShortArray>()
    private val sink: (ShortArray, Int) -> Unit = { samples, length -> chunks += samples.copyOf(length) }

    /** Absolute index of the next captured sample; sample values encode their index. */
    private var captured = 0L

    @Test
    fun `holds audio from just before the wake phrase and flushes it before live audio`() {
        router.enableWake(detector, HEY_WAKEY, 0.5f, events::add)
        assertTrue(router.micNeeded)
        feed(100)
        var detectedAt = 0L
        detector.duringAccept = { now += 7; detectedAt = now } // 7 ms of decoding
        detector.detectNext = WakeDetection("HEY WAKEY", keywordStartLag = 16_000, keywordEndLag = 4_800)
        feed()
        val event = events.single()
        assertEquals("HEY WAKEY", event.keyword)
        assertEquals(detectedAt, event.detectedAtMs)
        assertEquals(300L + 7, event.detectionLatencyMs)
        assertEquals(Mode.Holding, router.currentMode)

        val fedAtDetection = detector.fedSamples
        feed(10)
        router.startStream(sink)
        feed(21)
        assertEquals("no detection while holding or streaming", fedAtDetection, detector.fedSamples)
        val audio = streamed()
        val holdStart = 32_320L - 16_000 - CaptureRouter.HOLD_LEAD_SAMPLES
        assertArrayEquals(samples(holdStart, audio.size), audio)
        assertEquals((captured - holdStart) / CaptureRouter.STREAM_CHUNK_SAMPLES, chunks.size.toLong())
        assertTrue(chunks.all { it.size == CaptureRouter.STREAM_CHUNK_SAMPLES })
    }

    @Test
    fun `held audio never reaches back past the pre-roll`() {
        router.enableWake(detector, HEY_WAKEY, 0.5f, events::add)
        feed(200)
        detector.detectNext = WakeDetection("HEY WAKEY", keywordStartLag = 100_000, keywordEndLag = 90_000)
        feed()
        router.startStream(sink)
        feed()
        assertEquals(samples(captured - 320 - CaptureRouter.PRE_ROLL_SAMPLES, 1)[0], streamed()[0])
    }

    @Test
    fun `without timestamps the whole pre-roll is held, but never audio from before the detector restarted`() {
        router.enableWake(detector, HEY_WAKEY, 0.5f, events::add)
        feed(200)
        detector.detectNext = WakeDetection("HEY WAKEY", keywordStartLag = null, keywordEndLag = null)
        feed()
        assertEquals(null, events.single().detectionLatencyMs)
        router.startStream(sink)
        feed()
        assertEquals(samples(captured - 320 - CaptureRouter.PRE_ROLL_SAMPLES, 1)[0], streamed()[0])

        router.stopStream()
        chunks.clear()
        now += 5_000
        val restartedAt = captured
        feed(5)
        detector.detectNext = WakeDetection("HEY WAKEY", keywordStartLag = null, keywordEndLag = null)
        feed()
        router.startStream(sink)
        feed(4)
        assertEquals(samples(restartedAt, 1)[0], streamed()[0])
    }

    @Test
    fun `stopping the stream silences the sink and resumes detection on a fresh stream`() {
        router.enableWake(detector, HEY_WAKEY, 0.5f, events::add)
        feed(10)
        detector.detectNext = WakeDetection("HEY WAKEY", 8_000, 1_000)
        feed()
        router.startStream(sink)
        feed(5)
        val delivered = chunks.size
        val restarts = detector.restarts
        val fed = detector.fedSamples

        router.stopStream()
        assertEquals(Mode.Listening, router.currentMode)
        feed(10)
        assertEquals(delivered, chunks.size)
        assertEquals(restarts + 1, detector.restarts)
        assertEquals(fed + 3_200, detector.fedSamples)
    }

    @Test
    fun `stopping while holding discards the held audio`() {
        router.enableWake(detector, HEY_WAKEY, 0.5f, events::add)
        feed(50)
        detector.detectNext = WakeDetection("HEY WAKEY", 8_000, 1_000)
        feed()
        router.stopStream() // e.g. no Deepgram key: the orchestrator gives up on this wake
        assertEquals(Mode.Listening, router.currentMode)
        feed(2)
        val streamStart = captured
        router.startStream(sink)
        feed(4)
        assertEquals(samples(streamStart, 1)[0], streamed()[0])
    }

    @Test
    fun `unclaimed held audio is dropped after ten seconds`() {
        router.enableWake(detector, HEY_WAKEY, 0.5f, events::add)
        feed(10)
        detector.detectNext = WakeDetection("HEY WAKEY", 3_200, 1_000)
        feed()
        val fed = detector.fedSamples
        val restarts = detector.restarts
        // Held from sample 0 (the detector's start); the 490th block takes it past 160 000 samples.
        feed(489)
        assertEquals(Mode.Holding, router.currentMode)
        feed()
        assertEquals(Mode.Listening, router.currentMode)
        assertEquals(listOf("Wake detection was not claimed; listening again"), warnings)
        feed()
        assertEquals(restarts + 1, detector.restarts)
        assertEquals(fed + 320, detector.fedSamples)
    }

    @Test
    fun `detections closer than the debounce interval are ignored`() {
        router.enableWake(detector, HEY_WAKEY, 0.5f, events::add)
        feed(10)
        detector.detectNext = WakeDetection("HEY WAKEY", 8_000, 1_000)
        feed()
        router.stopStream()
        detector.detectNext = WakeDetection("HEY WAKEY", 8_000, 1_000)
        feed()
        assertEquals(1, events.size)
        assertEquals(Mode.Listening, router.currentMode)
        now += CaptureRouter.DEBOUNCE_MS
        detector.detectNext = WakeDetection("HEY WAKEY", 8_000, 1_000)
        feed()
        assertEquals(2, events.size)
    }

    @Test
    fun `push-to-talk streams live audio only and releases the mic`() {
        assertFalse(router.micNeeded)
        feed(3)
        router.startStream(sink)
        assertTrue(router.micNeeded)
        val streamStart = captured
        feed(8)
        assertArrayEquals(samples(streamStart, 2 * CaptureRouter.STREAM_CHUNK_SAMPLES), streamed())
        router.stopStream()
        assertEquals(Mode.Idle, router.currentMode)
        assertFalse(router.micNeeded)
    }

    @Test
    fun `keyword changes reach the detector on the audio thread, after any stream`() {
        router.enableWake(detector, HEY_WAKEY, 0.5f, events::add)
        assertTrue(detector.keywords.isEmpty())
        feed()
        assertEquals(listOf(HEY_WAKEY to 0.5f), detector.keywords)

        router.updateKeyword(HELLO_COMPUTER, 0.8f)
        feed()
        assertEquals(HELLO_COMPUTER to 0.8f, detector.keywords.last())

        router.startStream(sink)
        router.updateKeyword(HEY_WAKEY, 0.2f)
        feed(3)
        assertEquals(2, detector.keywords.size)
        router.stopStream()
        feed()
        assertEquals(HEY_WAKEY to 0.2f, detector.keywords.last())
    }

    @Test
    fun `disabling wake while holding goes idle`() {
        router.enableWake(detector, HEY_WAKEY, 0.5f, events::add)
        feed(10)
        detector.detectNext = WakeDetection("HEY WAKEY", 8_000, 1_000)
        feed()
        router.disableWake()
        assertEquals(Mode.Idle, router.currentMode)
        assertFalse(router.micNeeded)
        val fed = detector.fedSamples
        feed(5)
        assertEquals(fed, detector.fedSamples)
        assertEquals(1, events.size)
        router.enableWake(detector, HELLO_COMPUTER, 0.5f, events::add)
        feed()
        assertEquals(HELLO_COMPUTER to 0.5f, detector.keywords.last())
        assertEquals(fed + 320, detector.fedSamples)
    }

    @Test
    fun `a reopened mic restarts the detector stream`() {
        router.enableWake(detector, HEY_WAKEY, 0.5f, events::add)
        feed(3)
        val restarts = detector.restarts
        router.onMicOpened()
        feed()
        assertEquals(restarts + 1, detector.restarts)
        router.startStream(sink)
        router.onMicOpened() // push-to-talk reopening the mic must not disturb the stream
        assertEquals(Mode.Streaming, router.currentMode)
    }

    @Test
    fun `a detection decoded while a stream started is dropped`() {
        router.enableWake(detector, HEY_WAKEY, 0.5f, events::add)
        feed(10)
        detector.duringAccept = { router.startStream(sink) } // push-to-talk pressed mid-decode
        detector.detectNext = WakeDetection("HEY WAKEY", 8_000, 1_000)
        feed()
        assertTrue(events.isEmpty())
        assertEquals(Mode.Streaming, router.currentMode)
    }

    @Test
    fun `a failing sink or detector does not stop capture`() {
        router.startStream { _, _ -> throw IllegalStateException("closed") }
        feed(20)
        assertEquals(listOf("Command audio sink failed"), warnings)
        router.stopStream()

        router.enableWake(detector, HEY_WAKEY, 0.5f, events::add)
        detector.duringAccept = { throw IllegalStateException("native failure") }
        feed(2)
        assertEquals(listOf("Command audio sink failed", "Wake detector failed", "Wake detector failed"), warnings)
    }

    private fun feed(blocks: Int = 1) {
        repeat(blocks) {
            val block = samples(captured, BLOCK)
            router.onAudio(block, BLOCK, now)
            captured += BLOCK
            now += 20
        }
    }

    private fun samples(from: Long, count: Int) = ShortArray(count) { ((from + it) % 30_000).toInt().toShort() }

    private fun streamed() = chunks.fold(ShortArray(0)) { acc, chunk -> acc + chunk }

    private class FakeDetector : WakeWordDetector {
        val keywords = mutableListOf<Pair<EncodedKeyword, Float>>()
        var restarts = 0
        var fedSamples = 0L
        var detectNext: WakeDetection? = null
        var duringAccept: () -> Unit = {}

        override fun setKeyword(keyword: EncodedKeyword, sensitivity: Float) {
            keywords += keyword to sensitivity
            restarts++
        }

        override fun restart() {
            restarts++
        }

        override fun accept(samples: ShortArray, count: Int): WakeDetection? {
            fedSamples += count
            duringAccept()
            return detectNext.also { detectNext = null }
        }

        override fun close() = Unit
    }

    private companion object {
        const val BLOCK = 320
        val HEY_WAKEY = EncodedKeyword("HEY WAKEY", listOf("▁HE", "Y", "▁WA", "KE", "Y"))
        val HELLO_COMPUTER = EncodedKeyword("HELLO COMPUTER", listOf("▁HE", "LL", "O", "▁COMP", "U", "TER"))
    }
}
