package ai.wakey.android.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Automatic gain for the microphone, the way phone assistants prepare audio before listening:
 * quiet speech is raised towards a steady level before the wake word detector and speech
 * recognition hear it, so nobody has to raise their voice on a phone with a quiet microphone.
 * Android's voice recognition input (the one Wakey records from) has no gain control of its own.
 *
 * - Boost only: the gain never drops below 1×, so a loud microphone sounds exactly as before.
 * - It follows the level of speech (blocks well above the room's noise floor), not of noise, and
 *   caps the boost so the noise floor stays below [NOISE_CEILING_DB].
 * - The boost rises slowly and falls fast; a block whose peak would clip gets less gain.
 * - `hold` freezes what it has learned, e.g. while Wakey itself is talking into the microphone.
 *
 * Processes 16-bit PCM blocks in place, on the capture thread. Not thread-safe.
 */
class SpeechGain(private val sampleRate: Int = AudioEngine.SAMPLE_RATE) {
    /** The boost applied to the last block, in dB; 0 leaves the audio unchanged. */
    var gainDb = 0.0
        private set

    /** Running estimates, in dBFS, of the background noise and of speech. */
    private var noiseDb = INITIAL_NOISE_DB
    private var speechDb = INITIAL_SPEECH_DB

    /** Linear gain at the end of the last block, where the next block's ramp starts. */
    private var applied = 1.0

    fun process(samples: ShortArray, length: Int, hold: Boolean = false) {
        if (length <= 0) return
        var sumSquares = 0.0
        var peak = 0
        for (i in 0 until length) {
            val sample = samples[i].toInt()
            sumSquares += sample.toDouble() * sample
            peak = max(peak, abs(sample))
        }
        // Digital silence (a muted microphone) stays silent and says nothing about the room.
        if (peak == 0) return
        if (!hold) adapt(dbfs(sqrt(sumSquares / length)), length.toDouble() / sampleRate)
        apply(samples, length, peak)
    }

    private fun adapt(levelDb: Double, seconds: Double) {
        noiseDb = if (levelDb < noiseDb) {
            noiseDb + (levelDb - noiseDb) * min(1.0, seconds / NOISE_FALL_S)
        } else {
            min(levelDb, noiseDb + NOISE_RISE_DB_PER_S * seconds)
        }
        if (levelDb > noiseDb + SPEECH_OVER_NOISE_DB && levelDb > MIN_SPEECH_DB) {
            val timeConstant = if (levelDb > speechDb) SPEECH_ATTACK_S else SPEECH_RELEASE_S
            speechDb += (levelDb - speechDb) * min(1.0, seconds / timeConstant)
        }
        val wanted = (TARGET_SPEECH_DB - speechDb).coerceIn(0.0, MAX_GAIN_DB)
            .coerceAtMost(max(0.0, NOISE_CEILING_DB - noiseDb))
        gainDb = if (wanted > gainDb) {
            min(wanted, gainDb + GAIN_RISE_DB_PER_S * seconds)
        } else {
            max(wanted, gainDb - GAIN_FALL_DB_PER_S * seconds)
        }
    }

    private fun apply(samples: ShortArray, length: Int, peak: Int) {
        var target = linear(gainDb)
        // A peak that would clip gets less gain in this block, and the boost backs off for the next.
        if (peak * target > PEAK_LIMIT) {
            target = max(1.0, PEAK_LIMIT / peak)
            gainDb = dbOf(target)
        }
        // Ramp from the last block's gain so a change never clicks, unless that gain would clip here.
        val start = if (peak * applied > PEAK_LIMIT) target else applied
        applied = target
        if (start == 1.0 && target == 1.0) return
        val step = (target - start) / length
        for (i in 0 until length) {
            val scaled = (samples[i] * (start + step * (i + 1))).roundToInt()
            samples[i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    internal companion object {
        /** Where speech is brought to: loud syllables around -24 dBFS, leaving room for peaks. */
        const val TARGET_SPEECH_DB = -24.0
        const val MAX_GAIN_DB = 18.0

        /** Boosted background noise stays below this, so a noisy room isn't made noisier. */
        const val NOISE_CEILING_DB = -42.0

        /** Assumed before any speech was heard: a somewhat quiet microphone, about +10 dB. */
        const val INITIAL_SPEECH_DB = -34.0
        const val INITIAL_NOISE_DB = -60.0

        /** Blocks this far over the noise floor count as speech. */
        const val SPEECH_OVER_NOISE_DB = 10.0
        const val MIN_SPEECH_DB = -70.0
        const val SPEECH_ATTACK_S = 0.4
        const val SPEECH_RELEASE_S = 2.5
        const val NOISE_FALL_S = 0.2
        const val NOISE_RISE_DB_PER_S = 1.5
        const val GAIN_RISE_DB_PER_S = 6.0
        const val GAIN_FALL_DB_PER_S = 30.0

        /** About -1 dBFS. */
        const val PEAK_LIMIT = 29_000.0

        fun dbfs(rms: Double): Double = 20 * log10(rms / 32_768.0 + 1e-9)
        fun linear(db: Double): Double = 10.0.pow(db / 20)
        fun dbOf(linear: Double): Double = 20 * log10(linear)
    }
}
