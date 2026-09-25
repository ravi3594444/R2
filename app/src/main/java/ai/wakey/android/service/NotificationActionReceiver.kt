package ai.wakey.android.service

import ai.wakey.android.WakeyApp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles notification buttons: Stop (cancel the current task), Turn off (end listening),
 * Approve / Deny on confirmation prompts, and Run now / Snooze / Cancel / Done on task
 * notifications. Not exported, so it only receives the explicit intents built by [intent].
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = WakeyApp.graph
        when (intent.action) {
            ACTION_CANCEL_TASK -> graph.controller.stop()
            ACTION_TURN_OFF -> graph.controller.turnOff()
            ACTION_CONFIRM -> {
                if (!intent.hasExtra(EXTRA_CONFIRM_ID) || !intent.hasExtra(EXTRA_APPROVED)) return
                val id = intent.getLongExtra(EXTRA_CONFIRM_ID, 0)
                // A stale id (already answered or timed out) is ignored by the controller.
                graph.controller.answerConfirmation(id, intent.getBooleanExtra(EXTRA_APPROVED, false))
                graph.notifications.cancelConfirmation(id)
            }
            ACTION_TASK_RUN_NOW, ACTION_TASK_SNOOZE, ACTION_TASK_CANCEL, ACTION_TASK_DISMISS -> {
                if (!intent.hasExtra(EXTRA_TASK_ID)) return
                val id = intent.getLongExtra(EXTRA_TASK_ID, 0)
                graph.notifications.cancelTask(id)
                when (intent.action) {
                    ACTION_TASK_RUN_NOW -> graph.controller.runTaskNow(id)
                    ACTION_TASK_SNOOZE -> graph.controller.snoozeTask(id)
                    ACTION_TASK_CANCEL -> graph.controller.cancelTask(id)
                    ACTION_TASK_DISMISS -> graph.controller.dismissTask(id)
                }
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_TASK = "ai.wakey.android.action.CANCEL_TASK"
        const val ACTION_TURN_OFF = "ai.wakey.android.action.TURN_OFF"
        const val ACTION_CONFIRM = "ai.wakey.android.action.CONFIRM"
        const val ACTION_TASK_RUN_NOW = "ai.wakey.android.action.TASK_RUN_NOW"
        const val ACTION_TASK_SNOOZE = "ai.wakey.android.action.TASK_SNOOZE"
        const val ACTION_TASK_CANCEL = "ai.wakey.android.action.TASK_CANCEL"
        const val ACTION_TASK_DISMISS = "ai.wakey.android.action.TASK_DISMISS"
        const val EXTRA_CONFIRM_ID = "confirm_id"
        const val EXTRA_APPROVED = "approved"
        const val EXTRA_TASK_ID = "task_id"

        /** An explicit intent for [action]; implicit broadcasts never reach this unexported receiver. */
        fun intent(context: Context, action: String): Intent =
            Intent(context, NotificationActionReceiver::class.java).setAction(action)
    }
}
