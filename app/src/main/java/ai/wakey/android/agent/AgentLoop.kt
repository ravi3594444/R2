package ai.wakey.android.agent

import ai.wakey.android.accessibility.ScreenController
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.llm.ChatModel

/**
 * Bounded observe → decide → act → verify loop over the Accessibility UI tree, with a screenshot
 * fallback. Cancellation of the calling coroutine stops it before the next action.
 * STUB: implemented by the agent module.
 */
class AgentLoop(
    private val model: ChatModel,
    private val device: DeviceActions,
    private val screen: () -> ScreenController?,
    private val settings: () -> WakeySettings,
) {
    suspend fun run(goal: String, listener: AgentListener): AgentResult =
        AgentResult("Agent not implemented", AgentStatus.Failed, 0, 0, 0, 0, null)
}
