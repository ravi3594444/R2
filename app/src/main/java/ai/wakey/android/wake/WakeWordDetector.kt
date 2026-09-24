package ai.wakey.android.wake

import java.io.Closeable

/** A streaming wake-word detector. Not thread-safe: after setup, use it from the audio thread only. */
interface WakeWordDetector : Closeable {
    /** Listens for [keyword] from now on (0 = fewest false wakes, 1 = most eager). Restarts the stream. */
    fun setKeyword(keyword: EncodedKeyword, sensitivity: Float)

    /** Forgets all audio and decoder state, e.g. after a gap in the audio it was fed. */
    fun restart()

    /** Feeds 16 kHz mono PCM. Returns the detection completed by this audio, if any. */
    fun accept(samples: ShortArray, count: Int): WakeDetection?
}

/**
 * A detected wake phrase. Lags count back, in samples, from the end of the audio accepted so far;
 * they are null when the detector could not place the keyword in time.
 */
data class WakeDetection(
    /** Normalised phrase, e.g. "HEY WAKEY". */
    val phrase: String,
    val keywordStartLag: Long?,
    val keywordEndLag: Long?,
    /** A loose sound-alike fired: confirm with speech recognition before responding. */
    val needsCheck: Boolean = false,
)
