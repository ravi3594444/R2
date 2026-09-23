package ai.wakey.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmBuffersTest {
    @Test
    fun `ring keeps the newest samples by absolute index`() {
        val ring = PcmRingBuffer(10)
        ring.write(ramp(0, 7))
        assertEquals(0L, ring.start)
        assertArrayEquals(ramp(0, 7), ring.copyFrom(0))
        ring.write(ramp(7, 7))
        assertEquals(14L, ring.end)
        assertEquals(4L, ring.start)
        assertArrayEquals(ramp(4, 10), ring.copyFrom(0)) // clamped to what is still held
        assertArrayEquals(ramp(9, 5), ring.copyFrom(9))
        assertEquals(0, ring.copyFrom(20).size)
    }

    @Test
    fun `ring write longer than capacity keeps the tail`() {
        val ring = PcmRingBuffer(4)
        ring.write(ramp(0, 3))
        ring.write(ramp(3, 9), offset = 1, length = 7) // samples 4..10
        assertEquals(10L, ring.end)
        assertArrayEquals(ramp(7, 4), ring.copyFrom(0))
    }

    @Test
    fun `chunker emits exact chunks in order as fresh arrays`() {
        val chunker = PcmChunker(1280)
        val chunks = mutableListOf<ShortArray>()
        chunker.push(ramp(0, 1000)) { chunks += it }
        assertTrue(chunks.isEmpty())
        chunker.push(ramp(1000, 1000)) { chunks += it }
        chunker.push(ramp(2000, 600)) { chunks += it }
        assertEquals(2, chunks.size)
        assertArrayEquals(ramp(0, 1280), chunks[0])
        assertArrayEquals(ramp(1280, 1280), chunks[1])
        assertNotSame(chunks[0], chunks[1])
    }

    @Test
    fun `chunker clear drops the partial chunk`() {
        val chunker = PcmChunker(4)
        val chunks = mutableListOf<ShortArray>()
        chunker.push(ramp(0, 3)) { chunks += it }
        chunker.clear()
        chunker.push(ramp(10, 4)) { chunks += it }
        assertEquals(1, chunks.size)
        assertArrayEquals(ramp(10, 4), chunks[0])
    }

    @Test
    fun `level meter publishes at 15 Hz whatever the block size`() {
        for (block in listOf(320, 160, 1000)) {
            val meter = LevelMeter()
            var published = 0
            repeat(16_000 * 4 / block) { if (meter.add(ShortArray(block), block) != null) published++ }
            assertEquals("block $block", 60, published)
        }
    }

    @Test
    fun `level meter maps loudness to 0 to 1`() {
        assertEquals(0f, settledLevel(0.0), 1e-6f)
        assertEquals(1f, settledLevel(0.5), 1e-3f) // -9 dBFS RMS, above the -10 dBFS ceiling
        assertEquals(0.4f, settledLevel(0.01 * Math.sqrt(2.0)), 0.02f) // -40 dBFS RMS
    }

    @Test
    fun `level meter rises faster than it falls`() {
        val meter = LevelMeter()
        val loud = sine(0.5, 1067)
        val afterLoud = meter.add(loud, loud.size)!!
        val quiet = ShortArray(1067)
        val afterQuiet = meter.add(quiet, quiet.size)!!
        // Fraction of the way to the target covered in one update: 0 -> 1, then back towards 0.
        val riseFraction = afterLoud
        val fallFraction = (afterLoud - afterQuiet) / afterLoud
        assertTrue("rise $riseFraction vs fall $fallFraction", riseFraction > fallFraction)
    }

    private fun settledLevel(amplitude: Double): Float {
        val meter = LevelMeter()
        var level = 0f
        repeat(100) { val s = sine(amplitude, 320); meter.add(s, s.size)?.let { level = it } }
        return level
    }

    private fun sine(amplitude: Double, count: Int) =
        ShortArray(count) { (amplitude * 32_767 * Math.sin(2 * Math.PI * 440 * it / 16_000.0)).toInt().toShort() }

    private fun ramp(from: Int, count: Int) = ShortArray(count) { (from + it).toShort() }
}
