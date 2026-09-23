package ai.wakey.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsTest {
    @Test
    fun confirmationRequestCodesAreUniquePerIdAndAnswer() {
        val codes = (1L..500L).flatMap { id ->
            listOf(Notifications.confirmationRequestCode(id, true), Notifications.confirmationRequestCode(id, false))
        }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun confirmationRequestCodesStayClearOfFixedCodes() {
        for (id in listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 999_999L, 1_000_000L)) {
            for (approved in listOf(true, false)) {
                val code = Notifications.confirmationRequestCode(id, approved)
                assertTrue("code $code for id $id", code >= 1_000)
            }
        }
    }

    @Test
    fun approveAndDenyForOneIdDiffer() {
        assertNotEquals(Notifications.confirmationRequestCode(42, true), Notifications.confirmationRequestCode(42, false))
    }

    @Test
    fun firstUpdatePostsImmediately() {
        assertEquals(0L, Notifications.throttleDelayMs(lastPostAtMs = null, nowMs = 10, intervalMs = 500))
    }

    @Test
    fun updateInsideIntervalWaitsForTheRemainder() {
        assertEquals(300L, Notifications.throttleDelayMs(lastPostAtMs = 1_000, nowMs = 1_200, intervalMs = 500))
    }

    @Test
    fun updateAfterIntervalPostsImmediately() {
        assertEquals(0L, Notifications.throttleDelayMs(lastPostAtMs = 1_000, nowMs = 1_500, intervalMs = 500))
        assertEquals(0L, Notifications.throttleDelayMs(lastPostAtMs = 1_000, nowMs = 9_000, intervalMs = 500))
    }

    @Test
    fun delayNeverExceedsTheInterval() {
        assertEquals(500L, Notifications.throttleDelayMs(lastPostAtMs = 5_000, nowMs = 1_000, intervalMs = 500))
    }
}
