package ai.wakey.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CaptureLoopTest {
    private val delays = mutableListOf<Long>()
    private val problems = mutableListOf<String?>()
    private val warnings = mutableListOf<String>()
    private var delivered = 0
    private var reopened = 0
    private var opens = 0
    private val opened = mutableListOf<FakeMic>()

    /** What each open() does, in order: returns the mic it produces or throws. */
    private val openResults = ArrayDeque<() -> FakeMic>()

    /** Runs during each retry delay, after the delay is recorded. */
    private var duringDelay: () -> Unit = {}

    private val waiter = object : CaptureLoop.Waiter {
        override fun await(ms: Long) {
            delays += ms
            duringDelay()
        }

        override fun wake() = Unit
    }

    private val loop = CaptureLoop(
        blockSamples = BLOCK,
        open = {
            opens++
            (openResults.removeFirstOrNull() ?: error("unexpected open"))().also { opened += it }
        },
        onAudio = { _, count -> delivered += count },
        onReopened = { reopened++ },
        onProblem = { problems += it },
        warn = { message, _ -> warnings += message },
        waiter = waiter,
    )

    @Test
    fun `reopens the mic after a read error`() {
        val first = mic(BLOCK, BLOCK, DEAD_OBJECT)
        openResults += { mic(BLOCK, BLOCK) }
        loop.run(first)

        assertEquals(4 * BLOCK, delivered)
        assertEquals(1, reopened)
        assertEquals(listOf(200L), delays)
        assertEquals(1, first.released)
        assertEquals(1, opened.single().released)
        assertTrue("a quick recovery is not a problem", problems.isEmpty())
        assertEquals(listOf("Microphone read failed (-6); reopening it"), warnings)
        assertFalse(loop.isRunning)
    }

    @Test
    fun `reports a lasting failure and clears it once audio flows again`() {
        repeat(3) { openResults += { throw IllegalStateException(BUSY) } }
        openResults += { mic(BLOCK) }
        loop.run(mic(DEAD_OBJECT))

        assertEquals(listOf(200L, 500L, 1_000L, 2_000L), delays)
        assertEquals(listOf(BUSY, null), problems)
        assertEquals(1, reopened)
        assertEquals(BLOCK, delivered)
    }

    @Test
    fun `keeps retrying at the longest delay`() {
        repeat(8) { openResults += { throw IllegalStateException(BUSY) } }
        duringDelay = { if (delays.size == 8) loop.stop() }
        loop.run(mic(DEAD_OBJECT))

        assertEquals(listOf(200L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 10_000L, 10_000L), delays)
        assertEquals(7, opens)
        assertEquals("reported once", listOf(BUSY), problems)
    }

    @Test
    fun `a mic that fails again right after reopening keeps backing off`() {
        repeat(3) { openResults += { mic(DEAD_OBJECT) } }
        openResults += { mic(BLOCK) }
        loop.run(mic(DEAD_OBJECT))

        assertEquals(listOf(200L, 500L, 1_000L, 2_000L), delays)
        assertEquals(4, reopened)
        assertEquals(listOf(CaptureLoop.MIC_LOST, null), problems)
        assertTrue(opened.all { it.released == 1 })
    }

    @Test
    fun `failures without a readable message are reported generically`() {
        repeat(3) { openResults += { throw IllegalArgumentException("native detail") } }
        duringDelay = { if (delays.size == 4) loop.stop() }
        loop.run(mic(DEAD_OBJECT))
        assertEquals(listOf(CaptureLoop.MIC_LOST), problems)
    }

    @Test
    fun `empty reads count as a failure`() {
        val first = mic(*IntArray(CaptureLoop.MAX_ZERO_READS))
        openResults += { mic(BLOCK) }
        loop.run(first)
        assertEquals(1, reopened)
        assertEquals(BLOCK, delivered)
        assertEquals(1, first.released)
    }

    @Test
    fun `stopping during a read ends the loop without reopening`() {
        val first = mic(BLOCK)
        loop.run(first)

        assertEquals(BLOCK, delivered)
        assertEquals("stop unblocks the read", 1, first.stopped)
        assertEquals(1, first.released)
        assertEquals(0, opens)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `stopping during a retry delay ends the loop without reopening`() {
        duringDelay = { loop.stop() }
        loop.run(mic(DEAD_OBJECT))
        assertEquals(0, opens)
        assertTrue(problems.isEmpty())
    }

    @Test
    fun `a mic that opens while the loop stops is released unused`() {
        val late = mic(BLOCK)
        openResults += {
            loop.stop()
            late
        }
        loop.run(mic(DEAD_OBJECT))
        assertEquals(1, late.released)
        assertEquals(0, late.reads)
        assertEquals(0, reopened)
    }

    @Test
    fun `retryNow and stop cut a real retry delay short`() {
        val attempts = AtomicInteger()
        val real = CaptureLoop(
            BLOCK,
            open = {
                attempts.incrementAndGet()
                throw IllegalStateException(BUSY)
            },
            onAudio = { _, _ -> }, onReopened = {}, onProblem = {}, delaysMs = longArrayOf(60_000),
        )
        val first = FakeMic(listOf(DEAD_OBJECT))
        val thread = Thread { real.run(first) }.apply { start() }
        // Without retryNow, each attempt would wait a minute.
        val deadline = System.currentTimeMillis() + 5_000
        while (attempts.get() < 3 && System.currentTimeMillis() < deadline) {
            real.retryNow()
            Thread.sleep(5)
        }
        assertTrue(attempts.get() >= 3)
        real.stop()
        thread.join(2_000)
        assertFalse(thread.isAlive)
        assertEquals(1, first.released)
    }

    /** A mic that returns [reads] and then, like a stopped AudioRecord, stops the loop and fails. */
    private fun mic(vararg reads: Int) = FakeMic(reads.toList()) { loop.stop() }

    private class FakeMic(reads: List<Int>, private val onExhausted: () -> Unit = {}) : MicInput {
        private val script = ArrayDeque(reads)
        var reads = 0
        var stopped = 0
        var released = 0

        override fun read(buffer: ShortArray): Int {
            reads++
            return script.removeFirstOrNull() ?: run {
                onExhausted()
                INVALID_OPERATION
            }
        }

        override fun stop() {
            stopped++
        }

        override fun release() {
            released++
        }
    }

    private companion object {
        const val BLOCK = 320
        const val DEAD_OBJECT = -6
        const val INVALID_OPERATION = -3
        const val BUSY = "Could not open the microphone. Another app may be using it."
    }
}
