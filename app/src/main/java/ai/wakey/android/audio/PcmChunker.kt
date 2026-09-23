package ai.wakey.android.audio

/**
 * Re-slices a PCM stream into chunks of exactly [chunkSamples]. Every emitted chunk is a new array,
 * so consumers may queue it without copying. Not thread-safe.
 */
class PcmChunker(private val chunkSamples: Int) {
    private var pending = ShortArray(chunkSamples)
    private var filled = 0

    init {
        require(chunkSamples > 0) { "chunkSamples must be positive" }
    }

    fun push(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset, emit: (ShortArray) -> Unit) {
        var from = offset
        val until = offset + length
        while (from < until) {
            val count = minOf(chunkSamples - filled, until - from)
            System.arraycopy(samples, from, pending, filled, count)
            filled += count
            from += count
            if (filled == chunkSamples) {
                val chunk = pending
                pending = ShortArray(chunkSamples)
                filled = 0
                emit(chunk)
            }
        }
    }

    /** Drops a partial chunk. */
    fun clear() {
        filled = 0
    }
}
