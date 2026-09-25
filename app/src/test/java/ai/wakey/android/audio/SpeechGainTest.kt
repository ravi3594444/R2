package ai.wakey.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SpeechGainTest {
    private val rate = AudioEngine.SAMPLE_RATE
    private val block = 320

    @Test
    fun quietSpeechIsRaisedTowardsTheTarget() {
        val gain = SpeechGain()
        val input = speech(speechDb = -44.0, noiseDb = -65.0, seconds = 20.0)
        val output = run(gain, input)
        assertEquals(SpeechGain.MAX_GAIN_DB, gain.gainDb, 1.0)
        // The last burst comes out near the target level instead of 20 dB under it.
        val lastBurst = burstRms(output, fromSecond = 19.0)
        assertEquals(-24.0 - 2.0, SpeechGain.dbfs(lastBurst), 3.0)
    }

    @Test
    fun loudSpeechIsLeftAlone() {
        val gain = SpeechGain()
        val input = speech(speechDb = -18.0, noiseDb = -60.0, seconds = 8.0)
        val output = run(gain, input.copyOf())
        assertEquals(0.0, gain.gainDb, 0.01)
        val tail = input.size - rate * 2
        assertArrayEquals(input.copyOfRange(tail, input.size), output.copyOfRange(tail, output.size))
    }

    @Test
    fun aNoisyRoomIsNotMadeNoisier() {
        val gain = SpeechGain()
        run(gain, speech(speechDb = -38.0, noiseDb = -48.0, seconds = 20.0))
        // 14 dB would bring this speech to the target, but the noise may only rise to the ceiling.
        assertTrue("gain ${gain.gainDb}", gain.gainDb <= SpeechGain.NOISE_CEILING_DB + 48.0 + 0.5)
    }

    @Test
    fun aSuddenLoudSoundNeverClips() {
        val gain = SpeechGain()
        run(gain, speech(speechDb = -44.0, noiseDb = -65.0, seconds = 15.0))
        assertTrue(gain.gainDb > 15.0)
        val bang = tone(amplitude = 20_000.0, seconds = 0.5)
        val output = run(gain, bang)
        assertTrue("peak ${output.maxOf { abs(it.toInt()) }}", output.all { abs(it.toInt()) <= SpeechGain.PEAK_LIMIT + 1 })
        assertTrue(gain.gainDb < 1.0)
    }

    @Test
    fun silenceStaysSilentAndTeachesNothing() {
        val gain = SpeechGain()
        run(gain, speech(speechDb = -44.0, noiseDb = -65.0, seconds = 15.0))
        val learned = gain.gainDb
        val output = run(gain, ShortArray(rate * 3))
        assertTrue(output.all { it.toInt() == 0 })
        assertEquals(learned, gain.gainDb, 0.0)
    }

    @Test
    fun holdKeepsTheBoostWhileWakeyTalks() {
        val held = SpeechGain()
        val free = SpeechGain()
        val quiet = speech(speechDb = -44.0, noiseDb = -65.0, seconds = 15.0)
        run(held, quiet.copyOf())
        run(free, quiet.copyOf())
        // Wakey's own voice, louder than the user's.
        val reply = speech(speechDb = -30.0, noiseDb = -65.0, seconds = 3.0)
        run(held, reply.copyOf(), hold = true)
        run(free, reply.copyOf())
        assertEquals(SpeechGain.MAX_GAIN_DB, held.gainDb, 1.0)
        assertTrue("free ${free.gainDb}", free.gainDb < held.gainDb - 6.0)
    }

    @Test
    fun gainChangesAreRampedWithinABlock() {
        val gain = SpeechGain()
        val input = tone(amplitude = 1_000.0, seconds = 2.0, frequency = 0.0)
        val output = run(gain, input)
        // A constant input shows the gain directly: it may only move a little from sample to sample.
        val steps = (1 until output.size).map { abs(output[it] - output[it - 1]) }
        assertTrue("largest step ${steps.max()}", steps.max() <= 2)
    }

    private fun run(gain: SpeechGain, samples: ShortArray, hold: Boolean = false): ShortArray {
        var offset = 0
        while (offset < samples.size) {
            val length = minOf(block, samples.size - offset)
            val chunk = samples.copyOfRange(offset, offset + length)
            gain.process(chunk, length, hold)
            chunk.copyInto(samples, offset)
            offset += length
        }
        return samples
    }

    /** Bursts of a voice-like tone (300 ms on, 200 ms off) over steady noise. */
    private fun speech(speechDb: Double, noiseDb: Double, seconds: Double, seed: Int = 7): ShortArray {
        val random = Random(seed)
        val speechAmp = 32_768.0 * 10.0.pow(speechDb / 20) * sqrt(2.0)
        val noiseAmp = 32_768.0 * 10.0.pow(noiseDb / 20) * sqrt(3.0)
        return ShortArray((seconds * rate).toInt()) { i ->
            val inBurst = (i % (rate / 2)) < rate * 3 / 10
            val voice = if (inBurst) speechAmp * sin(2 * PI * 220 * i / rate) else 0.0
            (voice + noiseAmp * (random.nextDouble() * 2 - 1)).roundToInt().coerceIn(-32_768, 32_767).toShort()
        }
    }

    private fun tone(amplitude: Double, seconds: Double, frequency: Double = 440.0): ShortArray =
        ShortArray((seconds * rate).toInt()) { i ->
            (if (frequency == 0.0) amplitude else amplitude * sin(2 * PI * frequency * i / rate)).roundToInt().toShort()
        }

    /** RMS of the 300 ms burst that starts at [fromSecond] (a multiple of half a second). */
    private fun burstRms(samples: ShortArray, fromSecond: Double): Double {
        val start = (fromSecond * rate).toInt()
        val end = start + rate * 3 / 10
        val sum = (start until end).sumOf { samples[it].toDouble() * samples[it] }
        return sqrt(sum / (end - start))
    }
}
