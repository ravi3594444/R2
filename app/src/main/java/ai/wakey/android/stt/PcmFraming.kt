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
 * timestamps) back to when the caller pushed the frame holding it, and remembers each frame's
 * loudness to find where speech ended. Keeps the last [capacity] frames.
 */
internal class AudioTimeline(private val sampleRate: Int, private val capacity: Int) {
    private class Mark(val endSample: Long, val pushedAt: Long, val rms: Double)

    private val marks = ArrayDeque<Mark>()
    private var sentSamples = 0L
    private var forgottenUpTo = 0L

    /** Records that a frame of [samples] samples and loudness [rms], pushed at [pushedAt], was sent. */
    fun record(samples: Int, pushedAt: Long, rms: Double = 0.0) {
        sentSamples += samples
        marks.addLast(Mark(sentSamples, pushedAt, rms))
        if (marks.size > capacity) forgottenUpTo = marks.removeFirst().endSample
    }

    /** Push time of the frame containing the audio at [seconds], or null if unknown. */
    fun pushedAt(seconds: Double): Long? {
        val sample = Math.round(seconds * sampleRate)
        if (marks.isEmpty() || (forgottenUpTo > 0 && sample <= forgottenUpTo)) return null
        return (marks.firstOrNull { it.endSample >= sample } ?: marks.last()).pushedAt
    }

    /**
     * Push time of the last frame loud enough to be speech: [SPEECH_OVER_FLOOR] times the quietest
     * frame kept (the room's noise floor) and at least [MIN_SPEECH_RMS]. Null if no frame was.
     */
    fun lastSpeechPushedAt(): Long? {
        val floor = marks.minOfOrNull { it.rms } ?: return null
        val threshold = maxOf(floor * SPEECH_OVER_FLOOR, MIN_SPEECH_RMS)
        return marks.lastOrNull { it.rms >= threshold }?.pushedAt
    }

    companion object {
        /** About 12 dB over the noise floor. */
        const val SPEECH_OVER_FLOOR = 4.0

        /** About -50 dBFS; quieter frames never count as speech. */
        const val MIN_SPEECH_RMS = 100.0
    }
}

/** Root mean square of little-endian PCM16 [frame]. */
internal fun pcmRms(frame: ByteArray): Double {
    val samples = frame.size / 2
    if (samples == 0) return 0.0
    var sum = 0.0
    for (i in 0 until samples) {
        val sample = (frame[2 * i].toInt() and 0xFF) or (frame[2 * i + 1].toInt() shl 8)
        sum += sample.toDouble() * sample
    }
    return Math.sqrt(sum / samples)
}
