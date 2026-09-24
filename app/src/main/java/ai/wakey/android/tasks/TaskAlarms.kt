package ai.wakey.android.tasks

import ai.wakey.android.WakeyApp
import ai.wakey.android.ui.MainActivity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * [TaskWakeups] backed by AlarmManager: one exact alarm for the next due task. Alarms that ring use
 * `setAlarmClock` (never deferred by Doze, and shown as the next alarm); other tasks use
 * `setExactAndAllowWhileIdle`. Without exact-alarm access it falls back to an inexact alarm.
 */
class AlarmWakeups(private val context: Context) : TaskWakeups {
    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)
    private var scheduled: Pair<Long?, Boolean>? = null

    /** False when Android may deliver task alarms late (exact alarms were denied). */
    val exact: Boolean get() = alarmManager?.canScheduleExactAlarms() ?: false

    override fun reset() {
        scheduled = null
    }

    override fun wakeAt(epochMs: Long?, ringing: Boolean) {
        val request = epochMs to ringing
        if (request == scheduled) return
        val manager = alarmManager ?: return
        scheduled = request
        val operation = PendingIntent.getBroadcast(
            context, REQUEST_DUE,
            Intent(context, TaskAlarmReceiver::class.java).setAction(TaskAlarmReceiver.ACTION_DUE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (epochMs == null) {
            manager.cancel(operation)
            return
        }
        try {
            when {
                !manager.canScheduleExactAlarms() -> manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, operation)
                ringing -> manager.setAlarmClock(AlarmManager.AlarmClockInfo(epochMs, showTasksIntent()), operation)
                else -> manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, operation)
            }
        } catch (e: SecurityException) {
            // Exact alarms were revoked between the check and the call.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, operation)
        }
    }

    private fun showTasksIntent(): PendingIntent {
        val intent = Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, REQUEST_SHOW, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private companion object {
        const val REQUEST_DUE = 7_001
        const val REQUEST_SHOW = 7_002
    }
}

/** The task alarm fired: run whatever is due. Not exported; only Wakey's own PendingIntent reaches it. */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DUE) WakeyApp.graph.controller.onTaskAlarm()
    }

    companion object {
        const val ACTION_DUE = "ai.wakey.android.action.TASK_DUE"
    }
}

/**
 * Alarms don't survive a reboot, an app update or a clock change; starting the app graph re-arms
 * the next one, and anything that fell due meanwhile is run or reported missed.
 */
class TaskRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> WakeyApp.graph.controller.onTaskAlarm()
        }
    }
}
