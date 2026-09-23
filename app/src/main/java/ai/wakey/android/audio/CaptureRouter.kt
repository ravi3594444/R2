package ai.wakey.android.audio

import ai.wakey.android.wake.EncodedKeyword
import ai.wakey.android.wake.WakeDetection
import ai.wakey.android.wake.WakeWordDetector

/**
 * Decides where captured microphone audio goes: the Android-free core of [AudioEngine].
 *
 * - [Mode.Listening]: audio feeds the wake detector (and a pre-roll ring).
 * - [Mode.Holding]: after a detection, detection pauses and audio from just before the wake phrase
 *   onwards is kept until the command stream claims it (or [MAX_HELD_SAMPLES] pass unclaimed).
 * - [Mode.Streaming]: held audio, then live audio, goes to the sink in 80 ms chunks.
 *
 * [onAudio] runs on the audio thread and is the only caller of the detector, the sink and the wake
 * callback. The other methods are thread-safe; once [stopStream] returns the old sink gets no more audio.
 */
internal class CaptureRouter(
    /** Monotonic milliseconds (SystemClock.elapsedRealtime on device). */
    private val clock: () -> Long,
    private val warn: (String, Throwable?) -> Unit = { _, _ -> },
) {
    enum class Mode { Idle, Listening, Holding, Streaming }

    private val lock = Any()
    private val ring = PcmRingBuffer(RING_SAMPLES)
    private val chunker = PcmChunker(STREAM_CHUNK_SAMPLES)

    private var mode = Mode.Idle
    private var wakeEnabled = false
    private var detector: WakeWordDetector? = null
    private var onWake: ((WakeEvent) -> Unit)? = null
    private var pendingKeyword: Pair<EncodedKeyword, Float>? = null
    private var restartPending = false

    /** Bumped whenever detection is interrupted, so a detection decoded meanwhile is dropped. */
    private var epoch = 0

    /** Ring index where the detector's current stream began; held audio never reaches back further. */
    private var detectorStart = 0L
    private var holdStart = 0L
    private var flushFrom = NONE
    private var sink: ((ShortArray, Int) -> Unit)? = null
    private var sinkFailed = false
    private var lastDetectionMs = Long.MIN_VALUE / 2

    val currentMode: Mode get() = synchronized(lock) { mode }

    /** Whether the microphone must be open. */
    val micNeeded: Boolean get() = currentMode != Mode.Idle

    fun enableWake(detector: WakeWordDetector, keyword: EncodedKeyword, sensitivity: Float, onWake: (WakeEvent) -> Unit) {
        synchronized(lock) {
            this.detector = detector
            this.onWake = onWake
            pendingKeyword = keyword to sensitivity
            wakeEnabled = true
            if (mode == Mode.Idle) listen()
        }
    }

    /** Applied on the audio thread before the next detector input, even if a stream is running now. */
    fun updateKeyword(keyword: EncodedKeyword, sensitivity: Float) {
        synchronized(lock) { if (wakeEnabled) pendingKeyword = keyword to sensitivity }
    }

    fun disableWake() {
        synchronized(lock) {
            wakeEnabled = false
            onWake = null
            if (mode == Mode.Listening || mode == Mode.Holding) idle()
        }
    }

    fun startStream(sink: (ShortArray, Int) -> Unit) {
        synchronized(lock) {
            when (mode) {
                Mode.Holding -> flushFrom = holdStart
                Mode.Streaming -> Unit // A flush not yet delivered still belongs to this utterance.
                else -> flushFrom = NONE
            }
            if (mode != Mode.Streaming) chunker.clear()
            this.sink = sink
            sinkFailed = false
            mode = Mode.Streaming
            epoch++
        }
    }

    /** A new capture session started: the audio has a gap, so the detector must not continue across it. */
    fun onMicOpened() {
        synchronized(lock) { if (mode == Mode.Listening) listen() }
    }

    /** Discards held audio; resumes detection on a fresh detector stream if wake listening is on. */
    fun stopStream() {
        synchronized(lock) {
            if (mode != Mode.Streaming && mode != Mode.Holding) return
            sink = null
            flushFrom = NONE
            chunker.clear()
            if (wakeEnabled) listen() else idle()
        }
    }

    /** Audio thread: routes one captured block. [readAtMs] is the clock time the block was read. */
    fun onAudio(block: ShortArray, count: Int, readAtMs: Long) {
        val target: WakeWordDetector
        val keyword: Pair<EncodedKeyword, Float>?
        val restart: Boolean
        val feedEpoch: Int
        synchronized(lock) {
            ring.write(block, 0, count)
            when (mode) {
                Mode.Idle -> return
                Mode.Holding -> {
                    if (ring.end - holdStart > MAX_HELD_SAMPLES) {
                        warn("Wake detection was not claimed; listening again", null)
                        listen()
                    }
                    return
                }
                Mode.Streaming -> {
                    deliver(block, count)
                    return
                }
                Mode.Listening -> Unit
            }
            target = detector ?: return
            keyword = pendingKeyword
            pendingKeyword = null
            restart = restartPending
            restartPending = false
            if (keyword != null || restart) detectorStart = ring.end - count
            feedEpoch = epoch
        }
        val detection = try {
            if (keyword != null) target.setKeyword(keyword.first, keyword.second) else if (restart) target.restart()
            target.accept(block, count)
        } catch (e: RuntimeException) {
            warn("Wake detector failed", e)
            null
        } ?: return
        val (event, callback) = synchronized(lock) { claim(detection, feedEpoch, readAtMs) } ?: return
        try {
            callback(event)
        } catch (e: RuntimeException) {
            warn("Wake callback failed", e)
        }
    }

    private fun claim(detection: WakeDetection, feedEpoch: Int, readAtMs: Long): Pair<WakeEvent, (WakeEvent) -> Unit>? {
        if (mode != Mode.Listening || epoch != feedEpoch) return null
        val callback = onWake ?: return null
        val now = clock()
        if (now - lastDetectionMs < DEBOUNCE_MS) return null
        lastDetectionMs = now
        val lag = detection.keywordStartLag?.plus(HOLD_LEAD_SAMPLES) ?: PRE_ROLL_SAMPLES
        holdStart = maxOf(ring.end - minOf(lag, PRE_ROLL_SAMPLES), detectorStart, ring.start)
        mode = Mode.Holding
        epoch++
        val latencyMs = detection.keywordEndLag?.let { it * 1000 / AudioEngine.SAMPLE_RATE + (now - readAtMs) }
        return WakeEvent(detection.phrase, now, latencyMs) to callback
    }

    private fun deliver(block: ShortArray, count: Int) {
        val target = sink ?: return
        val emit = { chunk: ShortArray ->
            try {
                target(chunk, chunk.size)
            } catch (e: RuntimeException) {
                if (!sinkFailed) warn("Command audio sink failed", e)
                sinkFailed = true
            }
        }
        if (flushFrom != NONE) {
            // The ring already contains this block, so the flush covers it too.
            val held = ring.copyFrom(flushFrom)
            flushFrom = NONE
            chunker.push(held, emit = emit)
        } else {
            chunker.push(block, 0, count, emit)
        }
    }

    private fun listen() {
        mode = Mode.Listening
        restartPending = true
        epoch++
    }

    private fun idle() {
        mode = Mode.Idle
        epoch++
    }

    companion object {
        /** Audio kept before a detection: enough for a long phrase plus the detector's lag. */
        const val PRE_ROLL_SAMPLES = 24_000L

        /** Held audio starts this long before the wake phrase, so no word is cut in half. */
        const val HOLD_LEAD_SAMPLES = 3_200L

        /** Unclaimed held audio is dropped after this much (10 s). */
        const val MAX_HELD_SAMPLES = 160_000L

        /** 80 ms, the chunk size Deepgram recommends for streaming. */
        const val STREAM_CHUNK_SAMPLES = 1_280
        const val DEBOUNCE_MS = 1_500L

        /** 10 s of held audio plus 1 s of slack. */
        private const val RING_SAMPLES = 176_000
        private const val NONE = -1L
    }
}
