package ai.wakey.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Turns an accessibility screenshot into the small JPEG the vision model receives. */
object ScreenshotEncoder {
    /** Longest side of the JPEG, in pixels. */
    const val MAX_SIDE = 1280
    private const val JPEG_QUALITY = 70
    private const val CAPTURE_FAILED = "Couldn't capture the screen."

    /** [width]×[height] scaled down (never up) so the longest side is at most [maxSide]. */
    fun fitWithin(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** Result for an [AccessibilityService.takeScreenshot] failure code, with a user-facing reason. */
    fun failure(code: Int): ScreenshotResult = failure(
        when (code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "This screen is protected, so I can't capture it."
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                "Screenshots were taken too quickly. Try again in a moment."
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                "Screen capture isn't allowed yet. Turn Wakey screen control off and on again in Accessibility settings."
            else -> CAPTURE_FAILED
        }
    )

    /** JPEG-encodes a capture. The caller keeps ownership of [buffer] and closes it. Off the main thread. */
    fun encode(buffer: HardwareBuffer, colorSpace: ColorSpace?): ScreenshotResult {
        val hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return failure(CAPTURE_FAILED)
        val software = try {
            hardware.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            hardware.recycle()
        } ?: return failure(CAPTURE_FAILED)
        val (width, height) = fitWithin(software.width, software.height)
        val scaled = if (width == software.width && height == software.height) {
            software
        } else {
            Bitmap.createScaledBitmap(software, width, height, true).also { software.recycle() }
        }
        return try {
            val jpeg = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            ScreenshotResult(Base64.encodeToString(jpeg, Base64.NO_WRAP), width, height)
        } finally {
            scaled.recycle()
        }
    }

    private fun failure(message: String) = ScreenshotResult(null, 0, 0, message)
}
