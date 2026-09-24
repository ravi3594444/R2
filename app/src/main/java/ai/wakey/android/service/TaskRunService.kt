package ai.wakey.android.service

import ai.wakey.android.WakeyApp
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Keeps Wakey in the foreground while a scheduled task runs with no microphone service up, so the
 * task and its spoken reply aren't frozen mid-way, and shows which task runs with a Stop button.
 * Started from a task alarm, which Android allows for exact alarms. Stops itself once nothing is
 * running or queued; on Android 14+ it is a short service, which Android ends after a few minutes.
 */
class TaskRunService : LifecycleService() {
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (started) return START_NOT_STICKY
        val graph = WakeyApp.graph
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        try {
            ServiceCompat.startForeground(this, Notifications.TASK_RUN_ID, graph.notifications.buildTaskRun(graph.controller.tasks.value), type)
        } catch (e: IllegalStateException) {
            // Not allowed from the background right now; the task still runs, just without the notification.
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        lifecycleScope.launch {
            graph.controller.tasks.collect { board ->
                if (board.running == null && board.upNext.isEmpty()) shutDown() else graph.notifications.updateTaskRun(board)
            }
        }
        return START_NOT_STICKY
    }

    /** Android 14+ ends short services after about three minutes. */
    override fun onTimeout(startId: Int) = shutDown()

    private fun shutDown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        /** Returns false if Android refused the start. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, TaskRunService::class.java))
            true
        } catch (e: IllegalStateException) {
            false
        }
    }
}
