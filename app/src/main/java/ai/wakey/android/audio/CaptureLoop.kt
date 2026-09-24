package ai.wakey.android.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One open capture session (an AudioRecord on device). */
internal interface MicInput {
    /** Blocking read; returns the samples read, or a negative AudioRecord error code. */
    fun read(buffer: ShortArray): Int

    /** Unblocks a pending [read]. Called from another thread. */
    fun stop()

    /** Frees the session. Called on the capture thread after its last [read]. */
    fun release()
}

/**
 * The capture thread's body: reads the microphone and, when it fails (e.g. AudioRecord's
 * ERROR_DEAD_OBJECT after a phone call or an audio server restart), reopens it with growing delays
 * for as long as the loop runs. Android-free so recovery can be unit-tested.
 *
 * A failure that outlasts [PROBLEM_AFTER_FAILURES] consecutive failures is reported through
 * [onProblem], and cleared (null) once audio flows again. After [stop] returns, [onProblem] is not
 * called again.
 *
 * @param open opens a new session; throws [IllegalStateException] with a readable message on failure.
 * @param onAudio receives each block read, on the capture thread.
 * @param onReopened called after a replacement session opened: the audio has a gap.
 */
internal class CaptureLoop(
    private val blockSamples: Int,
    private val open: () -> MicInput,
    private val onAudio: (ShortArray, Int) -> Unit,
    private val onReopened: () -> Unit,
    private val onProblem: (String?) -> Unit,
    private val warn: (String, Throwable?) -> Unit = { _, _ -> },
    private val delaysMs: LongArray = RETRY_DELAYS_MS,
    private val waiter: Waiter = LatchWaiter(),
) {
    /** An interruptible sleep. */
    interface Waiter {
        /** Returns after [ms], or earlier once [wake] is called (also if it was called just before). */
        fun await(ms: Long)

        fun wake()
    }

    private val lock = Any()

    @Volatile
    private var running = true
    private var current: MicInput? = null
    private var problem: String? = null

    val isRunning: Boolean get() = running

    /** Reads [first], then its replacements, until [stop]. */
    fun run(first: MicInput) {
        var mic = adopt(first)
        // Consecutive failures: a failed read or reopen counts; only audio actually arriving resets it.
        var failures = 0
        var zeroReads = 0
        val buffer = ShortArray(blockSamples)
        while (running) {
            val input = mic
            if (input == null) {
                waiter.await(delaysMs[minOf(failures, delaysMs.size) - 1])
                if (!running) break
                var error: String? = null
                val opened = try {
                    open()
                } catch (e: RuntimeException) {
                    error = if (e is IllegalStateException) e.message else null
                    null
                }
                mic = opened?.let(::adopt)
                if (mic != null) onReopened() else if (opened == null) failed(++failures, error)
                continue
            }
            val read = input.read(buffer)
            if (!running) break
            if (read > 0) {
                zeroReads = 0
                if (failures > 0) {
                    failures = 0
                    report(null)
                }
                onAudio(buffer, read)
                continue
            }
            // A blocking read returns 0 only when the session has stopped delivering audio.
            if (read == 0 && ++zeroReads < MAX_ZERO_READS) continue
            warn("Microphone read failed ($read); reopening it", null)
            zeroReads = 0
            drop(input)
            mic = null
            failed(++failures, null)
        }
        mic?.let(::drop)
    }

    /** Ends a pending retry delay so the next attempt happens now (e.g. the user asked to talk). */
    fun retryNow() = waiter.wake()

    /** Makes [run] return soon: unblocks a pending read and ends a retry delay. Thread-safe. */
    fun stop() {
        synchronized(lock) {
            running = false
            current?.stop()
        }
        waiter.wake()
    }

    private fun failed(failures: Int, error: String?) {
        if (failures >= PROBLEM_AFTER_FAILURES) report(error ?: MIC_LOST)
    }

    private fun report(message: String?) {
        synchronized(lock) {
            if (!running || message == problem) return
            problem = message
            onProblem(message)
        }
    }

    /** Makes [mic] the session [stop] unblocks; releases it instead if the loop already stopped. */
    private fun adopt(mic: MicInput): MicInput? {
        synchronized(lock) {
            if (running) {
                current = mic
                return mic
            }
        }
        mic.release()
        return null
    }

    private fun drop(mic: MicInput) {
        synchronized(lock) { if (current === mic) current = null }
        mic.release()
    }

    /** [Waiter] on a lock condition; a [wake] shortly before [await] is not lost. */
    private class LatchWaiter : Waiter {
        private val lock = ReentrantLock()
        private val woken = lock.newCondition()
        private var pending = false

        override fun await(ms: Long) {
            lock.withLock {
                var left = TimeUnit.MILLISECONDS.toNanos(ms)
                while (!pending && left > 0) left = woken.awaitNanos(left)
                pending = false
            }
        }

        override fun wake() {
            lock.withLock {
                pending = true
                woken.signalAll()
            }
        }
    }

    companion object {
        /** An audio server restart takes about a second; a phone call can hold the mic for minutes. */
        val RETRY_DELAYS_MS = longArrayOf(200, 500, 1_000, 2_000, 5_000, 10_000)

        /** The failed read plus three failed reopens: about 1.7 s with the default delays. */
        const val PROBLEM_AFTER_FAILURES = 4

        /** Empty reads in a row that count as a failure, so a dead session can't spin the thread. */
        const val MAX_ZERO_READS = 50
        const val MIC_LOST = "The microphone stopped working. Wakey keeps trying to reopen it."
    }
}
