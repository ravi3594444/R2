package ai.wakey.android.stt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmFramingTest {
    @Test
    fun `emits little-endian frames of the configured size across pushes`() {
        val chunker = PcmChunker(chunkSamples = 3)
        val frames = mutableListOf<ByteArray>()
        chunker.push(shortArrayOf(1, -2), 2) { frames += it }
        assertEquals(0, frames.size)
        chunker.push(shortArrayOf(0x1234, 0x7FFF, Short.MIN_VALUE, 9), 3) { frames += it }
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(1, 0, -2, -1, 0x34, 0x12), frames[0])
        assertArrayEquals(byteArrayOf(-1, 0x7F, 0, -128), chunker.drain())
        assertNull(chunker.drain())
    }

    @Test
    fun `80 ms at 16 kHz is 1280 samples`() {
        val chunker = PcmChunker(16_000 * FluxSession.CHUNK_MS / 1000)
        val frames = mutableListOf<ByteArray>()
        chunker.push(ShortArray(4_000), 4_000) { frames += it }
        assertEquals(listOf(2560, 2560, 2560), frames.map { it.size })
        assertEquals(160 * 2, chunker.drain()?.size)
    }

    @Test
    fun `emitted frames are not reused`() {
        val chunker = PcmChunker(chunkSamples = 1)
        val frames = mutableListOf<ByteArray>()
        chunker.push(shortArrayOf(1, 2), 2) { frames += it }
        assertArrayEquals(byteArrayOf(1, 0), frames[0])
        assertArrayEquals(byteArrayOf(2, 0), frames[1])
    }

    @Test
    fun `timeline maps audio seconds to the push time of the containing frame`() {
        val timeline = AudioTimeline(sampleRate = 16_000, capacity = 10)
        timeline.record(1280, pushedAt = 100) // 0.00–0.08 s
        timeline.record(1280, pushedAt = 200) // 0.08–0.16 s
        timeline.record(640, pushedAt = 300) // 0.16–0.20 s
        assertEquals(100L, timeline.pushedAt(0.0))
        assertEquals(100L, timeline.pushedAt(0.08))
        assertEquals(200L, timeline.pushedAt(0.081))
        assertEquals(300L, timeline.pushedAt(0.2))
        assertEquals(300L, timeline.pushedAt(0.5)) // past the end: the latest frame
    }

    @Test
    fun `timeline forgets the oldest frames beyond capacity`() {
        val timeline = AudioTimeline(sampleRate = 1_000, capacity = 2)
        assertNull(timeline.pushedAt(0.0))
        timeline.record(100, pushedAt = 1)
        timeline.record(100, pushedAt = 2)
        timeline.record(100, pushedAt = 3)
        assertNull(timeline.pushedAt(0.05))
        assertEquals(2L, timeline.pushedAt(0.15))
        assertEquals(3L, timeline.pushedAt(0.3))
    }
}
