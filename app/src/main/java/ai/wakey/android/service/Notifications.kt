package ai.wakey.android.service

import ai.wakey.android.core.AssistantUiState
import android.app.Notification
import android.content.Context

/**
 * Notification channels, the persistent listening notification (with Stop), and heads-up
 * confirmation prompts with Approve / Deny actions for when Wakey is behind another app.
 * STUB: implemented by the wake-word/service module.
 */
class Notifications(private val context: Context) {
    fun ensureChannels() {}
    fun buildListening(state: AssistantUiState): Notification = throw UnsupportedOperationException()
    fun updateListening(state: AssistantUiState) {}
    fun showConfirmation(id: Long, question: String, detail: String) {}
    fun cancelConfirmation(id: Long) {}

    companion object {
        const val LISTENING_ID = 1001
    }
}
