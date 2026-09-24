package ai.wakey.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The short rising chime that tells the user Wakey heard the wake phrase and is listening, like
 * the tone other assistants play. It plays on the assistant audio stream, at the reply volume.
 * Thread-safe; failures are logged and never thrown.
 */
class WakeChime {
    private var track: AudioTrack? = null

    @Synchronized
    fun play() {
        try {
            val t = track ?: build().also { track = it }
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: RuntimeException) {
            // IllegalStateException / UnsupportedOperationException: no output available right now.
            Log.w(TAG, "Wake chime failed", e)
            track?.release()
            track = null
        }
    }

    @Synchronized
    fun release() {
        track?.release()
        track = null
    }

    private fun build(): AudioTrack {
        val pcm = pcm()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        return track
    }

    companion object {
        private const val TAG = "WakeyChime"
        const val RATE = 24_000

        /** (frequency Hz, start ms, length ms): G5 then D6, the second note ringing a little longer. */
        private val NOTES = listOf(Triple(784.0, 0, 90), Triple(1175.0, 70, 150))
        private const val PEAK = 0.22
        private const val ATTACK_MS = 6.0

        /** The chime as 16-bit mono PCM at [rate]: soft bell-like notes with no clicks at either end. */
        internal fun pcm(rate: Int = RATE): ShortArray {
            val totalMs = NOTES.maxOf { it.second + it.third }
            val out = DoubleArray(totalMs * rate / 1000)
            for ((freq, startMs, lengthMs) in NOTES) {
                val start = startMs * rate / 1000
                val length = lengthMs * rate / 1000
                for (i in 0 until min(length, out.size - start)) {
                    val tMs = i * 1000.0 / rate
                    val attack = min(1.0, tMs / ATTACK_MS)
                    val decay = (1.0 - i.toDouble() / length).pow(2)
                    out[start + i] += PEAK * attack * decay * sin(2 * PI * freq * i / rate)
                }
            }
            return ShortArray(out.size) { (out[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt().toShort() }
        }
    }
}
