package ai.wakey.android.agent

import ai.wakey.android.accessibility.ScreenController
import android.content.Context

/**
 * Direct Android actions: torch, app launch, home/back. App launches go through [screen] when the
 * accessibility service is bound, because it may start activities while Wakey is in the background.
 * STUB: agent module.
 */
class DeviceActions(private val context: Context, private val screen: () -> ScreenController?) {
    fun execute(command: FastCommand): ActionOutcome = ActionOutcome(false, "Not implemented")
    fun setTorch(on: Boolean): ActionOutcome = ActionOutcome(false, "Not implemented")
    fun openApp(name: String): ActionOutcome = ActionOutcome(false, "Not implemented")
    /** Launcher labels of installed apps, for STT keyterms and the model prompt. */
    fun installedAppLabels(): List<String> = emptyList()
}
