package ai.wakey.android.accessibility

import ai.wakey.android.agent.ActionOutcome
import android.content.Intent

/**
 * What the agent can see and do on screen, backed by [ai.wakey.android.WakeyAccessibilityService].
 * Element ids are only valid for the most recent [observe] result.
 */
interface ScreenController {
    /** Foreground app package, or null if unknown. */
    val foregroundPackage: String?

    /** True while the keyguard is showing. Wakey never tries to bypass it. */
    val isLocked: Boolean

    /** Reads the visible UI tree of the active window(s) into numbered elements. */
    suspend fun observe(maxElements: Int = 150): ScreenObservation

    /** Clicks the element (or its nearest clickable ancestor); falls back to a tap gesture at its centre. */
    suspend fun tap(target: ElementTarget): ActionOutcome

    /** Replaces the text of [target] (or the focused / first editable field) and optionally submits (IME enter). */
    suspend fun enterText(target: ElementTarget?, text: String, submit: Boolean): ActionOutcome

    suspend fun scroll(direction: ScrollDirection, target: ElementTarget?): ActionOutcome

    /**
     * Taps pixel ([x], [y]) of a screenshot that was [imageWidth]×[imageHeight], scaled to the real
     * screen. For controls the element list doesn't expose.
     */
    suspend fun tapPoint(x: Int, y: Int, imageWidth: Int, imageHeight: Int): ActionOutcome
    fun back(): ActionOutcome
    fun home(): ActionOutcome

    /** Screenshot as JPEG base64 (no data: prefix), longest side ≤ 1280 px. Secure windows return an error. */
    suspend fun screenshot(): ScreenshotResult

    /** Suspends until window content stops changing for a short quiet period, or [timeoutMs]. */
    suspend fun awaitSettled(timeoutMs: Long = 2_500)

    /** Starts an activity from the accessibility service context (allowed while Wakey is in the background). */
    fun launch(intent: Intent): Boolean
}

data class ElementTarget(val id: Int? = null, val label: String? = null)

enum class ScrollDirection { Up, Down, Left, Right }

data class ScreenElement(
    val id: Int,
    /** button, input, text, switch, checkbox, image, tab, list, item, … */
    val role: String,
    val text: String?,
    val description: String?,
    val hint: String?,
    val viewId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val enabled: Boolean,
    val focused: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class ScreenObservation(
    val packageName: String?,
    val appLabel: String?,
    val elements: List<ScreenElement>,
    /** True if the tree had more elements than were included. */
    val truncated: Boolean,
    /** Stable hash of the visible content, for repeated-screen detection. */
    val signature: String,
    /** Compact text rendering for the model, e.g. `[3] button "Bluetooth" (tap)`. */
    val text: String,
    /** Set when the tree is empty/unreadable (secure screen, lock screen, WebView without nodes). */
    val warning: String? = null,
)

data class ScreenshotResult(val jpegBase64: String?, val width: Int, val height: Int, val error: String? = null)
