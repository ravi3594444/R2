package ai.wakey.android.accessibility

import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenshotEncoderTest {

    @Test
    fun `images are scaled so the longest side is at most 1280 and never enlarged`() {
        assertEquals(576 to 1280, ScreenshotEncoder.fitWithin(1080, 2400))
        assertEquals(1280 to 576, ScreenshotEncoder.fitWithin(2400, 1080))
        assertEquals(1280 to 1280, ScreenshotEncoder.fitWithin(1440, 1440))
        assertEquals(800 to 600, ScreenshotEncoder.fitWithin(800, 600))
        assertEquals(720 to 1280, ScreenshotEncoder.fitWithin(720, 1280))
    }

    @Test
    fun `failures carry a user-facing reason and no image`() {
        val secure = ScreenshotEncoder.failure(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)
        assertEquals("This screen is protected, so I can't capture it.", secure.error)
        assertNull(secure.jpegBase64)
        assertEquals(0, secure.width)

        assertEquals(
            "Couldn't capture the screen.",
            ScreenshotEncoder.failure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR).error,
        )
    }
}
