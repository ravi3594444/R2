package ai.wakey.android.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Parser and segmentation on hand-built models; the real model is covered by [KeywordEncoderTest]. */
class SentencePieceModelTest {
    @Test
    fun `segments by best total score, not greedily`() {
        val model = model("▁" to -5f, "A" to -2f, "B" to -2f, "C" to -2f, "▁A" to -1f, "BC" to -1f, "▁AB" to -1.5f)
        // [▁A, BC] = -2 beats [▁AB, C] = -3.5, although ▁AB is the longest match at the start.
        assertEquals(listOf("▁A", "BC"), model.segment("▁ABC"))
    }

    @Test
    fun `ties keep the path found first, like sentencepiece`() {
        val model = model("▁A" to -1f, "B" to -1f, "▁AB" to -2f)
        assertEquals(listOf("▁AB"), model.segment("▁AB"))
    }

    @Test
    fun `only normal pieces are used`() {
        val model = SentencePieceModel.parse(
            modelProto(piece("<blk>", 0f, TYPE_USER_DEFINED), piece("▁AB", 0f, TYPE_CONTROL), piece("▁A", -1f), piece("B", -1f)),
        )
        assertEquals(listOf("▁A", "B"), model.segment("▁AB"))
        assertNull(model.segment("<blk>"))
    }

    @Test
    fun `returns null when a character has no piece`() {
        assertNull(model("▁A" to -1f).segment("▁A7"))
        assertNull(model("▁A" to -1f).segment(""))
    }

    @Test
    fun `skips unknown fields of every wire type`() {
        val bytes = concat(
            field(99, 1, ByteArray(8)), // fixed64
            modelProto(piece("▁A", -1f)),
            field(98, 0, varint(300)),
            field(97, 5, ByteArray(4)),
            field(4, 2, "self test".toByteArray()),
        )
        assertEquals(listOf("▁A"), SentencePieceModel.parse(bytes).segment("▁A"))
    }

    @Test
    fun `reads normalizer and trainer options`() {
        val noPrefix = concat(modelProto(piece("A", -1f)), field(3, 2, field(3, 0, varint(0))))
        assertFalse(SentencePieceModel.parse(noPrefix).addDummyPrefix)
        assertTrue(model("A" to -1f).addDummyPrefix)
        val bpe = concat(modelProto(piece("A", -1f)), field(2, 2, field(3, 0, varint(2))))
        val error = assertThrows(IllegalArgumentException::class.java) { SentencePieceModel.parse(bpe) }
        assertEquals("Unsupported SentencePiece model type 2; only unigram models are supported", error.message)
    }

    @Test
    fun `rejects malformed input`() {
        val good = modelProto(piece("▁A", -1f))
        assertThrows(IllegalArgumentException::class.java) { SentencePieceModel.parse(good.copyOf(good.size - 2)) }
        assertThrows(IllegalArgumentException::class.java) { SentencePieceModel.parse(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { SentencePieceModel.parse(byteArrayOf(0x0B)) } // group wire type
    }

    private companion object {
        const val TYPE_CONTROL = 3
        const val TYPE_USER_DEFINED = 4

        fun model(vararg pieces: Pair<String, Float>) =
            SentencePieceModel.parse(modelProto(*pieces.map { (text, score) -> piece(text, score) }.toTypedArray()))

        fun modelProto(vararg pieces: ByteArray) = concat(*pieces.map { field(1, 2, it) }.toTypedArray())

        fun piece(text: String, score: Float, type: Int? = null): ByteArray = concat(
            field(1, 2, text.toByteArray()),
            field(2, 5, fixed32(score.toRawBits())),
            type?.let { field(3, 0, varint(it.toLong())) } ?: ByteArray(0),
        )

        fun field(number: Int, wireType: Int, payload: ByteArray): ByteArray {
            val body = if (wireType == 2) concat(varint(payload.size.toLong()), payload) else payload
            return concat(varint((number shl 3 or wireType).toLong()), body)
        }

        fun varint(value: Long): ByteArray {
            val out = ByteArrayOutputStream()
            var v = value
            while (v >= 0x80) {
                out.write((v and 0x7F or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
            return out.toByteArray()
        }

        fun fixed32(bits: Int) = ByteArray(4) { (bits ushr (8 * it)).toByte() }

        fun concat(vararg parts: ByteArray) = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
    }
}
