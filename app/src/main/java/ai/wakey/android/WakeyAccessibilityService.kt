package ai.wakey.android

import ai.wakey.android.accessibility.ElementTarget
import ai.wakey.android.accessibility.ScreenController
import ai.wakey.android.accessibility.ScreenObservation
import ai.wakey.android.accessibility.ScreenshotResult
import ai.wakey.android.accessibility.ScrollDirection
import ai.wakey.android.agent.ActionOutcome
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/** STUB: implemented by the accessibility module. */
class WakeyAccessibilityService : AccessibilityService(), ScreenController {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override val foregroundPackage: String? get() = null
    override val isLocked: Boolean get() = false
    override suspend fun observe(maxElements: Int) = ScreenObservation(null, null, emptyList(), false, "", "", "Not implemented")
    override suspend fun tap(target: ElementTarget) = ActionOutcome(false, "Not implemented")
    override suspend fun enterText(target: ElementTarget?, text: String, submit: Boolean) = ActionOutcome(false, "Not implemented")
    override suspend fun scroll(direction: ScrollDirection, target: ElementTarget?) = ActionOutcome(false, "Not implemented")
    override fun back() = ActionOutcome(false, "Not implemented")
    override fun home() = ActionOutcome(false, "Not implemented")
    override suspend fun screenshot() = ScreenshotResult(null, 0, 0, "Not implemented")
    override suspend fun awaitSettled(timeoutMs: Long) = Unit
    override fun launch(intent: Intent) = false
    override fun showStatus(text: String, onStop: () -> Unit) = Unit
    override fun hideStatus() = Unit

    companion object {
        @Volatile var instance: WakeyAccessibilityService? = null
            private set

        /** The live screen controller, or null when the service is not enabled/bound. */
        val controller: ScreenController? get() = instance
    }
}
