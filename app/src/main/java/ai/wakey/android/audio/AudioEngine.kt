package ai.wakey.android.audio

import ai.wakey.android.wake.KeywordEncoder
import ai.wakey.android.wake.KeywordEncodingException
import ai.wakey.android.wake.SherpaWakeWordDetector
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * After a wake detection the engine keeps capturing and holds the audio from just before the wake
 * phrase onwards, so "Hey Wakey, turn on the flashlight" said in one breath is not clipped (the
 * orchestrator strips the phrase from the transcript). [startCommandStream] flushes that held audio
 * into the sink first. Detection pauses while audio is held or streamed.
 *
 * Public methods are meant for the main thread and are thread-safe. The wake callback and the sink
 * run on the audio thread.
 */
class AudioEngine(private val context: Context) {
    private val _level = MutableStateFlow(0f)

    /** Smoothed microphone loudness 0..1, updated ~15 times a second while the mic is open. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _wakeListening = MutableStateFlow(false)
    val wakeListening: StateFlow<Boolean> = _wakeListening.asStateFlow()

    private val router = CaptureRouter(SystemClock::elapsedRealtime) { message, error -> Log.w(TAG, message, error) }
    private var encoder: KeywordEncoder? = null
    private var detector: SherpaWakeWordDetector? = null
    private var capture: Capture? = null

    /** Starts capture + detection. Throws [IllegalStateException] with a readable message on failure. */
    @Synchronized
    fun startWakeListening(phrase: String, sensitivity: Float, onWake: (WakeEvent) -> Unit) {
        val keyword = try {
            encoder().encode(phrase)
        } catch (e: KeywordEncodingException) {
            throw IllegalStateException(e.message, e)
        }
        val detector = detector ?: SherpaWakeWordDetector.load(context.assets).also { detector = it }
        openMic()
        router.enableWake(detector, keyword, sensitivity, onWake)
        _wakeListening.value = true
    }

    /**
     * Rebuilds the keyword graph if wake listening is running.
     * @throws KeywordEncodingException if the phrase can't be used.
     */
    @Synchronized
    fun updateWakePhrase(phrase: String, sensitivity: Float) {
        if (!_wakeListening.value) return
        router.updateKeyword(encoder().encode(phrase), sensitivity)
    }

    @Synchronized
    fun stopWakeListening() {
        router.disableWake()
        _wakeListening.value = false
        closeMicIfUnused()
    }

    /**
     * Delivers held post-wake audio (if any), then live mic audio, to [sink] on the audio thread in
     * 80 ms chunks; each chunk is a new array. Opens the mic if wake listening is off (push-to-talk).
     */
    @Synchronized
    fun startCommandStream(sink: (ShortArray, Int) -> Unit) {
        openMic()
        router.startStream(sink)
    }

    /** Stops delivering to the sink; discards held audio; resumes wake detection if it is on. */
    @Synchronized
    fun stopCommandStream() {
        router.stopStream()
        closeMicIfUnused()
    }

    @Synchronized
    fun release() {
        router.disableWake()
        router.stopStream()
        _wakeListening.value = false
        closeMic()
        detector?.close()
        detector = null
    }

    private fun encoder(): KeywordEncoder = encoder ?: KeywordEncoder.fromAssets(context).also { encoder = it }

    // RECORD_AUDIO is checked explicitly before the AudioRecord is created.
    @SuppressLint("MissingPermission")
    private fun openMic() {
        if (capture?.isAlive == true) return
        closeMic()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("Wakey doesn't have microphone permission. Allow it in Settings › Apps › Wakey › Permissions.")
        }
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) throw IllegalStateException("This device can't record 16 kHz mono audio.")
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes * 2, RECORD_BUFFER_BYTES),
            )
        } catch (e: SecurityException) {
            throw IllegalStateException("Wakey doesn't have microphone permission.", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Could not configure the microphone.", e)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Could not open the microphone. Another app may be using it.")
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            throw IllegalStateException("Could not start the microphone.", e)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IllegalStateException("The microphone is busy. Close other apps that are recording and try again.")
        }
        capture = Capture(record)
        router.onMicOpened()
    }

    private fun closeMicIfUnused() {
        if (!router.micNeeded) closeMic()
    }

    private fun closeMic() {
        capture?.stop()
        capture = null
        _level.value = 0f
    }

    /** One AudioRecord session and its capture thread. */
    private inner class Capture(private val record: AudioRecord) {
        @Volatile
        private var running = true
        private val thread = Thread(::captureLoop, "wakey-audio").apply { start() }

        val isAlive: Boolean get() = running && thread.isAlive

        fun stop() {
            running = false
            try {
                record.stop() // Unblocks a pending read().
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord.stop failed", e)
            }
            thread.join(JOIN_TIMEOUT_MS)
            record.release()
        }

        private fun captureLoop() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not raise the capture thread priority", e)
            }
            val buffer = ShortArray(BLOCK_SAMPLES)
            val meter = LevelMeter()
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) {
                    Log.e(TAG, "Microphone read failed ($read); capture stopped")
                    break
                }
                if (read == 0) continue
                val readAt = SystemClock.elapsedRealtime()
                meter.add(buffer, read)?.let { _level.value = it }
                router.onAudio(buffer, read, readAt)
            }
            running = false
            _level.value = 0f
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 20 ms reads keep detection latency low without waking the CPU too often. */
        private const val BLOCK_SAMPLES = 320

        /** 320 ms, so a slow decode on the capture thread (one per 320 ms chunk) can't overrun it. */
        private const val RECORD_BUFFER_BYTES = SAMPLE_RATE * 2 * 320 / 1000
        private const val JOIN_TIMEOUT_MS = 1_000L
        private const val TAG = "WakeyAudio"
    }
}
