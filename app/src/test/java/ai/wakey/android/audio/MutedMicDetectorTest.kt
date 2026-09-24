package ai.wakey.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MutedMicDetectorTest {
    private val zeros = ShortArray(320)
    private val quietRoom = ShortArray(320) { if (it % 7 == 0) 2 else if (it % 5 == 0) -1 else 0 }

    @Test
    fun `seconds of exact zeros mean the microphone is muted`() {
        val detector = MutedMicDetector(mutedAfterSamples = 32_000)
        repeat(99) { assertNull(detector.add(zeros, zeros.size)) }
        assertEquals(true, detector.add(zeros, zeros.size))
        assertTrue(detector.muted)
        assertNull(detector.add(zeros, zeros.size))
    }

    @Test
    fun `any real sound clears it`() {
        val detector = MutedMicDetector(mutedAfterSamples = 640)
        detector.add(zeros, zeros.size)
        detector.add(zeros, zeros.size)
        assertTrue(detector.muted)
        assertEquals(false, detector.add(quietRoom, quietRoom.size))
        assertFalse(detector.muted)
    }

    @Test
    fun `a quiet room is not silence`() {
        val detector = MutedMicDetector(mutedAfterSamples = 3_200)
        repeat(1_000) { assertNull(detector.add(quietRoom, quietRoom.size)) }
        assertFalse(detector.muted)
    }

    @Test
    fun `trailing zeros of a block count toward the run`() {
        val detector = MutedMicDetector(mutedAfterSamples = 400)
        val block = ShortArray(320).also { it[0] = 5 }
        assertNull(detector.add(block, block.size))
        assertEquals(true, detector.add(zeros, 100))
    }
}
