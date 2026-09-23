package ai.wakey.android.wake

/**
 * The parts of a SentencePiece `ModelProto` needed to tokenise text, read with a minimal protobuf
 * decoder so the app needs no protobuf runtime.
 *
 * sherpa-onnx KWS models ship a *unigram* model despite the file name `bpe.model`, so segmentation
 * is sentencepiece's unigram Viterbi search; other model types are rejected rather than tokenised
 * differently from what the acoustic model was trained on.
 */
internal class SentencePieceModel private constructor(
    /** Scores of the normal pieces, the only ones a validated phrase can produce. */
    private val scores: Map<String, Float>,
    /** Whether sentencepiece prefixes the text with "▁" before segmenting. */
    val addDummyPrefix: Boolean,
) {
    private val maxPieceLength = scores.keys.maxOf { it.length }

    /**
     * Splits [text] (words already joined with "▁") into the pieces with the highest total score,
     * matching sentencepiece's `EncodeOptimized`: float sums, shorter pieces tried first, and a
     * later candidate only replaces the best path when strictly better. Returns null when some
     * character is covered by no piece (sentencepiece would emit `<unk>` there).
     */
    fun segment(text: String): List<String>? {
        val n = text.length
        val bestScore = FloatArray(n + 1)
        // Start index of the last piece on the best path ending at each position; -1 = unreachable.
        val pieceStart = IntArray(n + 1) { -1 }
        for (start in 0 until n) {
            if (start > 0 && pieceStart[start] < 0) continue
            val base = bestScore[start]
            for (end in start + 1..minOf(n, start + maxPieceLength)) {
                val score = scores[text.substring(start, end)] ?: continue
                val candidate = base + score
                if (pieceStart[end] < 0 || candidate > bestScore[end]) {
                    bestScore[end] = candidate
                    pieceStart[end] = start
                }
            }
        }
        if (n == 0 || pieceStart[n] < 0) return null
        val pieces = ArrayList<String>()
        var end = n
        while (end > 0) {
            val start = pieceStart[end]
            pieces += text.substring(start, end)
            end = start
        }
        return pieces.asReversed()
    }

    companion object {
        private const val PIECE_TYPE_NORMAL = 1
        private const val MODEL_TYPE_UNIGRAM = 1

        /** @throws IllegalArgumentException if [bytes] is not a unigram SentencePiece model. */
        fun parse(bytes: ByteArray): SentencePieceModel {
            val scores = HashMap<String, Float>()
            var modelType = MODEL_TYPE_UNIGRAM
            var addDummyPrefix = true
            ProtoReader(bytes, 0, bytes.size).forEachField { field, reader ->
                when (field) {
                    MODEL_PIECES -> readPiece(reader.message())?.let { (piece, score) -> scores[piece] = score }
                    MODEL_TRAINER_SPEC -> reader.message().forEachField { f, r ->
                        if (f == TRAINER_MODEL_TYPE) modelType = r.varint().toInt() else r.skip()
                    }
                    MODEL_NORMALIZER_SPEC -> reader.message().forEachField { f, r ->
                        if (f == NORMALIZER_ADD_DUMMY_PREFIX) addDummyPrefix = r.varint() != 0L else r.skip()
                    }
                    else -> reader.skip()
                }
            }
            require(modelType == MODEL_TYPE_UNIGRAM) {
                "Unsupported SentencePiece model type $modelType; only unigram models are supported"
            }
            require(scores.isNotEmpty()) { "SentencePiece model has no pieces" }
            return SentencePieceModel(scores, addDummyPrefix)
        }

        private fun readPiece(reader: ProtoReader): Pair<String, Float>? {
            var piece = ""
            var score = 0f
            var type = PIECE_TYPE_NORMAL
            reader.forEachField { field, r ->
                when (field) {
                    PIECE_TEXT -> piece = r.string()
                    PIECE_SCORE -> score = Float.fromBits(r.fixed32())
                    PIECE_TYPE -> type = r.varint().toInt()
                    else -> r.skip()
                }
            }
            return if (type == PIECE_TYPE_NORMAL && piece.isNotEmpty()) piece to score else null
        }

        // Field numbers from sentencepiece_model.proto.
        private const val MODEL_PIECES = 1
        private const val MODEL_TRAINER_SPEC = 2
        private const val MODEL_NORMALIZER_SPEC = 3
        private const val PIECE_TEXT = 1
        private const val PIECE_SCORE = 2
        private const val PIECE_TYPE = 3
        private const val TRAINER_MODEL_TYPE = 3
        private const val NORMALIZER_ADD_DUMMY_PREFIX = 3
    }
}

/** Reads protobuf wire format from `bytes[pos until end]`. Malformed input throws [IllegalArgumentException]. */
private class ProtoReader(private val bytes: ByteArray, private var pos: Int, private val end: Int) {
    private var wireType = -1

    /** Calls [block] for each field; the block must consume the value or call [skip]. */
    fun forEachField(block: (field: Int, reader: ProtoReader) -> Unit) {
        while (pos < end) {
            val tag = varintUnchecked()
            wireType = (tag and 7).toInt()
            block((tag ushr 3).toInt(), this)
        }
    }

    fun varint(): Long {
        expect(WIRE_VARINT)
        return varintUnchecked()
    }

    fun fixed32(): Int {
        expect(WIRE_FIXED32)
        require(end - pos >= 4) { "Truncated protobuf" }
        var value = 0
        for (i in 0 until 4) value = value or ((bytes[pos + i].toInt() and 0xFF) shl (8 * i))
        pos += 4
        return value
    }

    fun string(): String {
        val length = lengthDelimited()
        return String(bytes, pos - length, length, Charsets.UTF_8)
    }

    fun message(): ProtoReader {
        val length = lengthDelimited()
        return ProtoReader(bytes, pos - length, pos)
    }

    fun skip() {
        when (wireType) {
            WIRE_VARINT -> varintUnchecked()
            WIRE_FIXED64 -> advance(8)
            WIRE_LENGTH_DELIMITED -> lengthDelimited()
            WIRE_FIXED32 -> advance(4)
            else -> throw IllegalArgumentException("Unsupported protobuf wire type $wireType")
        }
    }

    private fun lengthDelimited(): Int {
        expect(WIRE_LENGTH_DELIMITED)
        val length = varintUnchecked()
        require(length in 0..(end - pos).toLong()) { "Truncated protobuf" }
        pos += length.toInt()
        return length.toInt()
    }

    private fun advance(count: Int) {
        require(end - pos >= count) { "Truncated protobuf" }
        pos += count
    }

    private fun varintUnchecked(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < end && shift < 64) { "Malformed protobuf varint" }
            val b = bytes[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun expect(type: Int) = require(wireType == type) { "Unexpected protobuf wire type $wireType" }

    private companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH_DELIMITED = 2
        const val WIRE_FIXED32 = 5
    }
}
