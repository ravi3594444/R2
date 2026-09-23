package ai.wakey.android.service

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeServiceTest {
    @Test
    fun missingMicPermissionAsksForAccess() {
        assertEquals(WakeService.MIC_PERMISSION_MESSAGE, WakeService.startFailureMessage(SecurityException("RECORD_AUDIO")))
    }

    @Test
    fun backgroundStartTellsTheUserToOpenWakey() {
        assertEquals(WakeService.BACKGROUND_START_MESSAGE, WakeService.startFailureMessage(IllegalStateException("not allowed")))
    }
}
