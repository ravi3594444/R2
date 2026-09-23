package ai.wakey.android.accessibility

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * Remembers when window content last changed so actions can wait for the UI to settle.
 * [markChanged] may be called from any thread.
 */
class SettleTracker(private val clock: () -> Long) {
    private val lastChange = AtomicLong(clock())

    fun markChanged() = lastChange.set(clock())

    /**
     * Waits at least [minWaitMs] (the app may not have reacted yet), then until nothing has changed
     * for [quietMs], or [timeoutMs] after the call. Cancellable.
     */
    suspend fun awaitSettled(timeoutMs: Long, minWaitMs: Long = MIN_WAIT_MS, quietMs: Long = QUIET_MS) {
        val deadline = clock() + timeoutMs.coerceAtLeast(0)
        delay(minOf(minWaitMs, timeoutMs.coerceAtLeast(0)))
        while (true) {
            val now = clock()
            val quietFor = now - lastChange.get()
            if (quietFor >= quietMs || now >= deadline) return
            delay(minOf(quietMs - quietFor, deadline - now))
        }
    }

    companion object {
        const val MIN_WAIT_MS = 200L
        const val QUIET_MS = 250L
    }
}
