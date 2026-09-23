package ai.wakey.android.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fired on the audio thread when the wake phrase is detected on device. */
data class WakeEvent(
    val keyword: String,
    /** SystemClock.elapsedRealtime() at detection. */
    val detectedAtMs: Long,
    /** Keyword audio end → detection, when the detector reports timestamps. */
    val detectionLatencyMs: Long?,
)

/**
 * Owns the microphone. Two modes that share one AudioRecord:
 *  - wake listening: audio goes only to the on-device sherpa-onnx detector (never off device);
 *  - command stream: audio goes to a sink (the cloud STT session) until [stopCommandStream].
 *
 * After a wake detection the engine keeps capturing and holds the audio that follows the keyword
 * (plus a short pre-roll) so "Hey Wakey, turn on the flashlight" said in one breath is not clipped.
 * [startCommandStream] flushes that held audio into the sink first.
 * STUB: implemented by the wake-word module.
 */
class AudioEngine(private val context: Context) {
    val level: StateFlow<Float> = MutableStateFlow(0f)
    val wakeListening: StateFlow<Boolean> = MutableStateFlow(false)

    /** Starts capture + detection. Throws [IllegalStateException] with a readable message on failure. */
    fun startWakeListening(phrase: String, sensitivity: Float, onWake: (WakeEvent) -> Unit) {}

    /** Rebuilds the keyword graph if wake listening is running. */
    fun updateWakePhrase(phrase: String, sensitivity: Float) {}

    fun stopWakeListening() {}

    /** Delivers held post-wake audio (if any), then live mic audio, to [sink] on the audio thread. */
    fun startCommandStream(sink: (ShortArray, Int) -> Unit) {}

    /** Stops delivering to the sink; discards held audio; resumes wake detection if it is on. */
    fun stopCommandStream() {}

    fun release() {}

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
