package ai.wakey.android.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Turns PCM into a smoothed 0..1 loudness for UI animation, published about [publishHz] times a
 * second of audio. Loudness is RMS on a -60..-10 dBFS scale, so normal speech moves the orb visibly.
 * Not thread-safe.
 */
class LevelMeter(sampleRate: Int = AudioEngine.SAMPLE_RATE, publishHz: Int = 15) {
    private val interval = sampleRate / publishHz
    private var sumSquares = 0.0
    private var windowSamples = 0
    private var due = 0
    private var smoothed = 0f

    /** Adds audio; returns the new level when one is due, otherwise null. */
    fun add(samples: ShortArray, length: Int): Float? {
        for (i in 0 until length) {
            val s = samples[i].toDouble()
            sumSquares += s * s
        }
        windowSamples += length
        due += length
        if (due < interval) return null
        // Carry the remainder so the average rate stays at publishHz with any block size.
        due %= interval
        val rms = sqrt(sumSquares / windowSamples) / 32_768.0
        sumSquares = 0.0
        windowSamples = 0
        val db = 20 * log10(rms + 1e-9)
        val target = ((db - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).toFloat().coerceIn(0f, 1f)
        smoothed += (target - smoothed) * if (target > smoothed) ATTACK else RELEASE
        return smoothed
    }

    private companion object {
        const val FLOOR_DB = -60.0
        const val CEILING_DB = -10.0
        const val ATTACK = 0.6f
        const val RELEASE = 0.3f
    }
}
