package ai.wakey.android.audio

/** The most recent [capacity] PCM samples, addressed by absolute sample index. Not thread-safe. */
class PcmRingBuffer(val capacity: Int) {
    private val data = ShortArray(capacity)

    /** Absolute index one past the newest sample (= samples written so far). */
    var end: Long = 0
        private set

    /** Absolute index of the oldest sample still held. */
    val start: Long get() = maxOf(0L, end - capacity)

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun write(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        var from = offset
        var count = length
        if (count > capacity) {
            // Only the newest [capacity] samples can survive.
            from += count - capacity
            end += count - capacity
            count = capacity
        }
        val pos = (end % capacity).toInt()
        val first = minOf(count, capacity - pos)
        System.arraycopy(samples, from, data, pos, first)
        System.arraycopy(samples, from + first, data, 0, count - first)
        end += count
    }

    /** Copies the samples from absolute index [from] (clamped to what is still held) up to [end]. */
    fun copyFrom(from: Long): ShortArray {
        val begin = from.coerceIn(start, end)
        val out = ShortArray((end - begin).toInt())
        val pos = (begin % capacity).toInt()
        val first = minOf(out.size, capacity - pos)
        System.arraycopy(data, pos, out, 0, first)
        System.arraycopy(data, 0, out, first, out.size - first)
        return out
    }
}
