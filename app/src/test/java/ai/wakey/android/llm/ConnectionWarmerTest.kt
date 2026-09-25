package ai.wakey.android.llm

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionWarmerTest {
    @Test
    fun warmsAtMostOncePerInterval() {
        var now = 1_000L
        val warmer = ConnectionWarmer(OkHttpClient(), intervalMs = 60_000) { now }
        val fired = mutableListOf<Long>()
        repeat(5) {
            if (warmer.due()) fired += now
            now += 20_000
        }
        // 1 s, then 61 s: every third 20 s tick.
        assertEquals(listOf(1_000L, 61_000L), fired)
    }
}
