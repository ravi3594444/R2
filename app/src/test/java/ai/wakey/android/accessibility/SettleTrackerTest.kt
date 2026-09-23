package ai.wakey.android.accessibility

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettleTrackerTest {
    private fun TestScope.tracker() = SettleTracker { currentTime }

    @Test
    fun `a quiet screen settles after the minimum wait plus the quiet period`() = runTest {
        val settle = tracker()
        settle.awaitSettled(2_500)
        assertEquals(SettleTracker.QUIET_MS, currentTime)
    }

    @Test
    fun `waits until changes stop, then a quiet period`() = runTest {
        val settle = tracker()
        launch {
            repeat(10) {
                delay(100)
                settle.markChanged()
            }
        }
        settle.awaitSettled(2_500)
        assertEquals(1_000 + SettleTracker.QUIET_MS, currentTime)
    }

    @Test
    fun `constant change gives up at the timeout`() = runTest {
        val settle = tracker()
        backgroundScope.launch {
            while (true) {
                delay(50)
                settle.markChanged()
            }
        }
        settle.awaitSettled(2_500)
        assertEquals(2_500L, currentTime)
    }

    @Test
    fun `a timeout shorter than the minimum wait is respected`() = runTest {
        tracker().awaitSettled(100)
        assertEquals(100L, currentTime)
    }
}
