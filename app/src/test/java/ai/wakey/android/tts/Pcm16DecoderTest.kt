package ai.wakey.android.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16DecoderTest {
    // Little-endian: 0x1234, -1, Short.MIN_VALUE, Short.MAX_VALUE, 0x0001.
    private val bytes = byteArrayOf(0x34, 0x12, -1, -1, 0x00, -128, -1, 0x7F, 0x01, 0x00)
    private val expected = shortArrayOf(0x1234, -1, Short.MIN_VALUE, Short.MAX_VALUE, 1)

    private fun decodeInReads(readSizes: List<Int>): ShortArray {
        val decoder = Pcm16Decoder()
        val out = mutableListOf<Short>()
        var offset = 0
        for (size in readSizes) {
            val chunk = bytes.copyOfRange(offset, offset + size)
            val samples = ShortArray((size + 1) / 2)
            val count = decoder.decode(chunk, size, samples)
            out += samples.take(count)
            offset += size
        }
        return out.toShortArray()
    }

    @Test
    fun decodesWholeBuffer() {
        assertArrayEquals(expected, decodeInReads(listOf(bytes.size)))
    }

    @Test
    fun carriesOddBytesAcrossEveryReadBoundary() {
        for (first in 0..bytes.size) {
            for (second in 0..bytes.size - first) {
                val sizes = listOf(first, second, bytes.size - first - second)
                assertArrayEquals("reads $sizes", expected, decodeInReads(sizes))
            }
        }
        assertArrayEquals(expected, decodeInReads(List(bytes.size) { 1 }))
    }

    @Test
    fun honoursLengthSmallerThanArray() {
        val samples = ShortArray(8)
        assertEquals(1, Pcm16Decoder().decode(bytes, 3, samples))
        assertEquals(0x1234.toShort(), samples[0])
    }
}
