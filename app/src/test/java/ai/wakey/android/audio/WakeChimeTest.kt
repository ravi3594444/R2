package ai.wakey.android.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WakeChimeTest {
    @Test
    fun `chime is short, soft and click-free`() {
        val pcm = WakeChime.pcm(24_000)
        assertTrue(pcm.size in 4_800..7_200) // 200-300 ms
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 3_000..16_000)
        assertTrue(abs(pcm.first().toInt()) < 200)
        assertTrue(pcm.takeLast(24).all { abs(it.toInt()) < 400 })
    }
}
