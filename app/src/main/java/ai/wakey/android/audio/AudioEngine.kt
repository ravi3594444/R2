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
    /** Only a loose sound-alike matched: confirm the phrase in the transcript before responding. */
    val needsCheck: Boolean = false,
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
 * If the microphone fails while open (e.g. the audio server restarted after a phone call), the engine
 * reopens it in the background with growing delays and reports a lasting failure in [micProblem].
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

    private val _micProblem = MutableStateFlow<String?>(null)

    /**
     * Why the open microphone delivers no audio, as a message for the user, or null. While it is set
     * the engine keeps reopening the microphone; it clears once audio flows again or the mic closes.
     */
    val micProblem: StateFlow<String?> = _micProblem.asStateFlow()

    private val _micMuted = MutableStateFlow(false)

    /**
     * True while the open microphone delivers only digital silence: Android is muting Wakey (the
     * microphone privacy toggle, or no right to record in the background). See [MutedMicDetector].
     */
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

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

    private fun openMic() {
        capture?.let {
            if (it.isAlive) {
                // It may be waiting to reopen a failed mic; audio is wanted now, so retry now.
                it.retryNow()
                return
            }
        }
        closeMic()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException(MIC_PERMISSION)
        }
        capture = Capture(openRecord())
        router.onMicOpened()
    }

    /**
     * Starts a new AudioRecord: VOICE_RECOGNITION (tuned for speech recognition), or MIC on devices
     * that can't provide it. Runs on the main thread for the first open and on the capture thread
     * when recovering.
     * @throws IllegalStateException with a readable message.
     */
    private fun openRecord(): MicInput {
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) throw IllegalStateException("This device can't record 16 kHz mono audio.")
        val bufferBytes = maxOf(minBytes * 2, RECORD_BUFFER_BYTES)
        var failure: IllegalStateException? = null
        for (source in AUDIO_SOURCES) {
            try {
                return AudioRecordInput(startRecord(source, bufferBytes))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Audio source $source unavailable", e)
                failure = e
            } catch (e: SecurityException) {
                throw IllegalStateException(MIC_PERMISSION, e)
            }
        }
        throw checkNotNull(failure)
    }

    // RECORD_AUDIO is checked before the first open; if it is revoked later this throws SecurityException.
    @SuppressLint("MissingPermission")
    private fun startRecord(source: Int, bufferBytes: Int): AudioRecord {
        val record = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
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
        return record
    }

    private fun closeMicIfUnused() {
        if (!router.micNeeded) closeMic()
    }

    private fun closeMic() {
        capture?.stop()
        capture = null
        _level.value = 0f
        _micProblem.value = null
        _micMuted.value = false
    }

    private class AudioRecordInput(private val record: AudioRecord) : MicInput {
        override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)

        override fun stop() {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord.stop failed", e)
            }
        }

        override fun release() = record.release()
    }

    /** The capture thread: reads the mic, and reopens it after failures, until [stop]. */
    private inner class Capture(first: MicInput) {
        private val meter = LevelMeter()
        private val muteDetector = MutedMicDetector()
        private val loop = CaptureLoop(
            blockSamples = BLOCK_SAMPLES,
            open = ::openRecord,
            onAudio = { buffer, count ->
                val readAt = SystemClock.elapsedRealtime()
                meter.add(buffer, count)?.let { _level.value = it }
                muteDetector.add(buffer, count)?.let { _micMuted.value = it }
                router.onAudio(buffer, count, readAt)
            },
            onReopened = router::onMicOpened,
            onProblem = { problem ->
                _micProblem.value = problem
                if (problem != null) _level.value = 0f
            },
            warn = { message, error -> Log.w(TAG, message, error) },
        )
        private val thread = Thread({ captureThread(first) }, "wakey-audio").apply { start() }

        val isAlive: Boolean get() = loop.isRunning && thread.isAlive

        fun retryNow() = loop.retryNow()

        /** The capture thread releases the AudioRecord itself once its read has returned. */
        fun stop() {
            loop.stop()
            thread.join(JOIN_TIMEOUT_MS)
        }

        private fun captureThread(first: MicInput) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not raise the capture thread priority", e)
            }
            loop.run(first)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 20 ms reads keep detection latency low without waking the CPU too often. */
        private const val BLOCK_SAMPLES = 320

        /**
         * 1 s. Wake detection decodes on the capture thread, a 320 ms chunk at a time (about 12 ms on
         * a desktop core), so the buffer absorbs a decode many times slower on a low-end phone.
         */
        private const val RECORD_BUFFER_BYTES = SAMPLE_RATE * 2
        private const val JOIN_TIMEOUT_MS = 1_000L
        private const val TAG = "WakeyAudio"
        private const val MIC_PERMISSION =
            "Wakey doesn't have microphone permission. Allow it in Settings › Apps › Wakey › Permissions."
        private val AUDIO_SOURCES = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
    }
}
