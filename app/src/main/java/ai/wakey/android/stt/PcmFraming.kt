package ai.wakey.android.stt

/** Re-frames PCM16 pushes of any size into fixed-size little-endian frames (Flux recommends 80 ms). */
internal class PcmChunker(chunkSamples: Int) {
    private val chunkBytes = chunkSamples * 2
    private var buffer = ByteArray(chunkBytes)
    private var filled = 0

    init {
        require(chunkSamples > 0) { "chunkSamples must be positive" }
    }

    /** Appends the first [length] samples; [emit] receives each completed frame and may keep it. */
    fun push(samples: ShortArray, length: Int, emit: (ByteArray) -> Unit) {
        for (i in 0 until length) {
            val sample = samples[i].toInt()
            buffer[filled++] = sample.toByte()
            buffer[filled++] = (sample shr 8).toByte()
            if (filled == chunkBytes) {
                emit(buffer)
                buffer = ByteArray(chunkBytes)
                filled = 0
            }
        }
    }

    /** Returns the incomplete frame, if any, and starts a new one. */
    fun drain(): ByteArray? {
        if (filled == 0) return null
        return buffer.copyOf(filled).also { filled = 0 }
    }
}

/**
 * Maps a position on the stream's audio clock (seconds of audio sent so far, as in Flux word
 * timestamps) back to when the caller pushed the frame holding it. Keeps the last [capacity] frames.
 */
internal class AudioTimeline(private val sampleRate: Int, private val capacity: Int) {
    private class Mark(val endSample: Long, val pushedAt: Long)

    private val marks = ArrayDeque<Mark>()
    private var sentSamples = 0L
    private var forgottenUpTo = 0L

    /** Records that a frame of [samples] samples, pushed at [pushedAt], was sent. */
    fun record(samples: Int, pushedAt: Long) {
        sentSamples += samples
        marks.addLast(Mark(sentSamples, pushedAt))
        if (marks.size > capacity) forgottenUpTo = marks.removeFirst().endSample
    }

    /** Push time of the frame containing the audio at [seconds], or null if unknown. */
    fun pushedAt(seconds: Double): Long? {
        val sample = Math.round(seconds * sampleRate)
        if (marks.isEmpty() || (forgottenUpTo > 0 && sample <= forgottenUpTo)) return null
        return (marks.firstOrNull { it.endSample >= sample } ?: marks.last()).pushedAt
    }
}
