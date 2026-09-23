package ai.wakey.android.wake

import kotlin.math.roundToLong

/** Where a detected keyword lies in a detector stream, in samples since the stream started. */
internal data class KeywordSpan(val startSample: Long, val endSample: Long)

/**
 * Places a sherpa-onnx keyword detection on the stream's timeline.
 *
 * sherpa reports token times relative to the last decoder reset, and it also resets itself after
 * 1.5 s of trailing blanks without telling the caller, so raw times drift away from the audio.
 * Resets only happen between decode calls, i.e. on chunk boundaries, so a token's frame within its
 * chunk is exact. The keyword fires [trailingBlanks] + 1 blank frames after its last token, which
 * fixes the chunk that token was decoded in relative to the chunk that reported the detection.
 */
internal object KeywordTiming {
    /** Encoder output frames per decode call: 32 feature frames (320 ms) after 4x subsampling. */
    const val FRAMES_PER_CHUNK = 8
    const val FRAME_SECONDS = 0.04
    const val FRAME_SAMPLES = 640L

    /**
     * @param timestamps token times in seconds from sherpa's `KeywordSpotterResult`.
     * @param decodedChunks decode calls on this stream so far, including the one that detected.
     * @return null if there are no timestamps or they are inconsistent with [decodedChunks].
     */
    fun locate(timestamps: FloatArray, decodedChunks: Long, trailingBlanks: Int): KeywordSpan? {
        if (timestamps.isEmpty()) return null
        val firstFrame = (timestamps.first() / FRAME_SECONDS).roundToLong()
        val lastFrame = (timestamps.last() / FRAME_SECONDS).roundToLong()
        val lastInChunk = lastFrame % FRAMES_PER_CHUNK
        val detectFrame = lastInChunk + trailingBlanks + 1
        val chunkOfLast = decodedChunks - 1 - detectFrame / FRAMES_PER_CHUNK
        val lastAbsolute = chunkOfLast * FRAMES_PER_CHUNK + lastInChunk
        val firstAbsolute = lastAbsolute - (lastFrame - firstFrame)
        if (firstAbsolute < 0 || lastFrame < firstFrame) return null
        return KeywordSpan(firstAbsolute * FRAME_SAMPLES, (lastAbsolute + 1) * FRAME_SAMPLES)
    }
}
