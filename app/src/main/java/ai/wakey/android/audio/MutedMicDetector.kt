package ai.wakey.android.audio

/**
 * Notices when an open microphone delivers exact digital silence. A real microphone always picks up
 * some noise, so seconds of zero samples mean Android is muting Wakey: the microphone privacy
 * toggle is off, or Wakey lost its right to record in the background. Reads keep succeeding in
 * that case, so [CaptureLoop] can't see it. Not thread-safe: use it from the capture thread.
 */
class MutedMicDetector(private val mutedAfterSamples: Int = AudioEngine.SAMPLE_RATE * 2) {
    private var zeroRun = 0L

    var muted = false
        private set

    /** Adds a block; returns the new state when it changes, otherwise null. */
    fun add(samples: ShortArray, length: Int): Boolean? {
        var lastNonZero = -1
        for (i in length - 1 downTo 0) {
            if (samples[i].toInt() != 0) {
                lastNonZero = i
                break
            }
        }
        zeroRun = if (lastNonZero < 0) zeroRun + length else (length - 1 - lastNonZero).toLong()
        val now = zeroRun >= mutedAfterSamples
        if (now == muted) return null
        muted = now
        return now
    }

    fun reset() {
        zeroRun = 0
        muted = false
    }
}
