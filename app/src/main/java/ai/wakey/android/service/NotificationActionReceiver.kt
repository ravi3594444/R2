package ai.wakey.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles notification buttons: Stop task, turn off listening, approve / deny confirmations. STUB. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}

    companion object {
        const val ACTION_CANCEL_TASK = "ai.wakey.android.action.CANCEL_TASK"
        const val ACTION_TURN_OFF = "ai.wakey.android.action.TURN_OFF"
        const val ACTION_CONFIRM = "ai.wakey.android.action.CONFIRM"
        const val EXTRA_CONFIRM_ID = "confirm_id"
        const val EXTRA_APPROVED = "approved"
    }
}
