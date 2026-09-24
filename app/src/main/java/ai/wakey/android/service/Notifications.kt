package ai.wakey.android.service

import ai.wakey.android.R
import ai.wakey.android.WakeyApp
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.tasks.TaskBoard
import ai.wakey.android.tasks.WakeyTask
import ai.wakey.android.ui.MainActivity
import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Notification channels, the persistent listening notification (with Stop and Turn off), heads-up
 * confirmation prompts with Approve / Deny actions for when Wakey is behind another app, and task
 * notifications: reminders, ringing alarms, scheduled tasks that need the phone unlocked, missed
 * tasks, and the results of tasks that ran on their own.
 *
 * Everything is private on a secure lock screen: the public version never shows speech, replies,
 * reminder text or the pending question. Call from the main thread only.
 *
 * @param wakePhrase read each time the listening notification is built.
 */
class Notifications(
    private val context: Context,
    private val wakePhrase: () -> String = { WakeyApp.graph.settings.current.wakePhrase },
) {
    private val manager = NotificationManagerCompat.from(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val openAppIntent by lazy {
        val intent = Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PendingIntent.getActivity(context, REQUEST_OPEN_APP, intent, PendingIntent.FLAG_IMMUTABLE)
    }
    private val cancelTaskIntent by lazy { broadcast(NotificationActionReceiver.ACTION_CANCEL_TASK, REQUEST_CANCEL_TASK) }
    private val turnOffIntent by lazy { broadcast(NotificationActionReceiver.ACTION_TURN_OFF, REQUEST_TURN_OFF) }

    // Listening-notification throttle: what is on screen, when it was posted, and the newest
    // content waiting for the interval to pass (non-null exactly while [flushPending] is queued).
    private var shown: ListeningContent? = null
    private var lastPostAtMs: Long? = null
    private var pending: ListeningContent? = null
    private val flushPending = Runnable {
        val content = pending ?: return@Runnable
        pending = null
        if (WakeService.isRunning) postListening(content)
    }

    /** Creates Wakey's channels. Idempotent. */
    fun ensureChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_LISTENING, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Listening")
                    .setDescription("Shown while Wakey listens for the wake word or keeps the floating button ready, with Stop and Turn off.")
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_CONFIRM, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Approvals")
                    .setDescription("Asks before Wakey sends, buys or changes something for you.")
                    // Wakey usually speaks the question aloud; a chime would talk over it.
                    .setSound(null, null)
                    .setVibrationEnabled(true)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_REMINDERS, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Reminders and scheduled tasks")
                    .setDescription("Reminders you asked for, and scheduled tasks that need you or didn't run.")
                    .setVibrationEnabled(true)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_ALARMS, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Alarms and timers")
                    .setDescription("Alarms and timers set with Wakey. They ring until you stop or snooze them.")
                    .setSound(
                        Settings.System.DEFAULT_ALARM_ALERT_URI,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setVibrationEnabled(true)
                    .setVibrationPattern(longArrayOf(0, 700, 500, 700))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_TASKS, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Running tasks")
                    .setDescription("Shown while Wakey runs a scheduled task in the background, with Stop.")
                    .setShowBadge(false)
                    .build(),
            ),
        )
    }

    /**
     * Builds the listening notification that [WakeService] passes to `startForeground`. The caller
     * posts it immediately, so it also becomes the baseline [updateListening] compares against.
     */
    fun buildListening(state: AssistantUiState): Notification {
        val content = listeningContent(state, wakePhrase(), state.wakeWordEnabled)
        dropPending()
        markShown(content)
        return listeningNotification(content)
    }

    /**
     * Refreshes the listening notification while [WakeService] is in the foreground. Cheap enough to
     * call on every state change: unchanged content is skipped, and posts are spaced
     * [MIN_UPDATE_INTERVAL_MS] apart (Android drops bursts of updates) with the newest content
     * posted once the interval has passed. Nothing is posted without notification permission.
     */
    fun updateListening(state: AssistantUiState) {
        if (!WakeService.isRunning) return
        val content = listeningContent(state, wakePhrase(), state.wakeWordEnabled)
        if (content == shown) {
            dropPending()
            return
        }
        val wait = throttleDelayMs(lastPostAtMs, SystemClock.elapsedRealtime(), MIN_UPDATE_INTERVAL_MS)
        if (wait == 0L) {
            dropPending()
            postListening(content)
        } else {
            if (pending == null) mainHandler.postDelayed(flushPending, wait)
            pending = content
        }
    }

    /**
     * Heads-up prompt for a consequential action. Approve requires unlocking the device; Deny does
     * not. Both answer through [NotificationActionReceiver]. Expires with the controller's timeout.
     */
    fun showConfirmation(id: Long, question: String, detail: String) {
        val title = clip(question.trim(), MAX_TITLE_CHARS)
        val body = clip(detail.trim(), MAX_DETAIL_CHARS).takeIf { it.isNotEmpty() }
        val publicVersion = confirmationBuilder()
            .setContentTitle("Wakey needs your approval")
            .setContentText("Unlock to review")
            .build()
        val approve = NotificationCompat.Action.Builder(0, "Approve", confirmIntent(id, approved = true))
            // Approving sends, buys or changes something, so it must never work from a locked screen.
            .setAuthenticationRequired(true)
            .build()
        val notification = confirmationBuilder()
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(body?.let { NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText(it) })
            .setPublicVersion(publicVersion)
            .setTimeoutAfter(CONFIRM_TIMEOUT_MS)
            .addAction(approve)
            .addAction(0, "Deny", confirmIntent(id, approved = false))
            .build()
        notify(confirmationTag(id), CONFIRM_ID, notification)
    }

    /** Removes the prompt for confirmation [id], whichever way it was answered. */
    fun cancelConfirmation(id: Long) = manager.cancel(confirmationTag(id), CONFIRM_ID)

    // ------------------------------------------------------------------ tasks

    /** A reminder's time came: its text, with Snooze and Done. */
    fun showReminder(task: WakeyTask, text: String) {
        val notification = taskBuilder(CHANNEL_REMINDERS, "Wakey reminder")
            .setContentTitle(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "Snooze 10 min", taskIntent(task.id, TaskAction.Snooze))
            .addAction(0, "Done", taskIntent(task.id, TaskAction.Dismiss))
            .build()
        notify(taskTag(task.id), TASK_ID, notification)
    }

    /** Rings until stopped, snoozed, opened or [ALARM_RING_MS] passes. */
    fun showAlarm(task: WakeyTask, title: String, time: String) {
        val notification = taskBuilder(CHANNEL_ALARMS, "Wakey alarm · $time")
            .setContentTitle(title)
            .setContentText(time)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setTimeoutAfter(ALARM_RING_MS)
            .setOnlyAlertOnce(false)
            .addAction(0, "Stop", taskIntent(task.id, TaskAction.Dismiss))
            .addAction(0, "Snooze 10 min", taskIntent(task.id, TaskAction.Snooze))
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        notify(taskTag(task.id), TASK_ID, notification)
    }

    /** A scheduled task is due but the phone is locked. Run now requires unlocking. */
    fun showTaskWaiting(task: WakeyTask, text: String) {
        val notification = taskBuilder(CHANNEL_REMINDERS, "Wakey has a task for you")
            .setContentTitle(text)
            .setContentText("Unlock your phone and Wakey will do it.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(runNowAction(task.id))
            .addAction(0, "Snooze 10 min", taskIntent(task.id, TaskAction.Snooze))
            .addAction(0, "Cancel", taskIntent(task.id, TaskAction.Cancel))
            .build()
        notify(taskTag(task.id), TASK_ID, notification)
    }

    /** A scheduled task that didn't run in time. */
    fun showTaskMissed(task: WakeyTask, text: String, reason: String) {
        val notification = taskBuilder(CHANNEL_REMINDERS, "Wakey missed a task")
            .setContentTitle(text)
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .addAction(runNowAction(task.id))
            .build()
        notify(taskTag(task.id), TASK_ID, notification)
    }

    /** What happened with a task that ran on its own, for when the spoken reply wasn't heard. */
    fun showTaskResult(task: WakeyTask, reply: String, failed: Boolean) {
        val notification = taskBuilder(CHANNEL_REMINDERS, if (failed) "A Wakey task didn't finish" else "Wakey finished a task")
            .setContentTitle(clip(task.title, MAX_TITLE_CHARS))
            .setContentText(reply)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reply))
            .setSilent(!failed)
            .apply { if (failed) addAction(runNowAction(task.id, label = "Try again")) }
            .build()
        notify(taskTag(task.id), TASK_ID, notification)
    }

    fun cancelTask(id: Long) = manager.cancel(taskTag(id), TASK_ID)

    /** The notification of [TaskRunService]: which task runs, with Stop. */
    fun buildTaskRun(board: TaskBoard): Notification {
        val title = board.running?.title ?: board.upNext.firstOrNull()?.title ?: "Scheduled task"
        return NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_stat_wakey)
            .setColor(ContextCompat.getColor(context, R.color.wakey_accent))
            .setContentTitle("Running a scheduled task")
            .setContentText(clip(title, MAX_TEXT_CHARS))
            .setContentIntent(openAppIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_TASKS)
                    .setSmallIcon(R.drawable.ic_stat_wakey)
                    .setContentTitle("Wakey is running a task")
                    .build(),
            )
            .addAction(0, "Stop", cancelTaskIntent)
            .build()
    }

    fun updateTaskRun(board: TaskBoard) = notify(null, TASK_RUN_ID, buildTaskRun(board))

    private fun taskBuilder(channel: String, publicTitle: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_wakey)
        .setColor(ContextCompat.getColor(context, R.color.wakey_accent))
        .setContentIntent(openAppIntent)
        .setAutoCancel(true)
        .setLocalOnly(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_wakey)
                .setContentTitle(publicTitle)
                .build(),
        )

    private fun runNowAction(id: Long, label: String = "Run now") =
        NotificationCompat.Action.Builder(0, label, taskIntent(id, TaskAction.RunNow))
            // Running a task operates the phone, so it must not start from a locked screen.
            .setAuthenticationRequired(true)
            .build()

    private fun taskIntent(id: Long, action: TaskAction): PendingIntent {
        val intent = NotificationActionReceiver.intent(context, action.intentAction)
            .putExtra(NotificationActionReceiver.EXTRA_TASK_ID, id)
        return PendingIntent.getBroadcast(
            context, taskRequestCode(id, action), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // ------------------------------------------------------------------ internals

    private fun listeningNotification(content: ListeningContent): Notification {
        val publicVersion = listeningBuilder(content.publicTitle).build()
        return listeningBuilder(content.title)
            .setContentText(content.text)
            .setStyle(content.text?.let { NotificationCompat.BigTextStyle().bigText(it) })
            .setPublicVersion(publicVersion)
            .build()
    }

    private fun listeningBuilder(title: String) = NotificationCompat.Builder(context, CHANNEL_LISTENING)
        .setSmallIcon(R.drawable.ic_stat_wakey)
        .setColor(ContextCompat.getColor(context, R.color.wakey_accent))
        .setContentTitle(title)
        .setContentIntent(openAppIntent)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setLocalOnly(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .addAction(0, "Stop", cancelTaskIntent)
        .addAction(0, "Turn off", turnOffIntent)

    private fun confirmationBuilder() = NotificationCompat.Builder(context, CHANNEL_CONFIRM)
        .setSmallIcon(R.drawable.ic_stat_wakey)
        .setColor(ContextCompat.getColor(context, R.color.wakey_accent))
        .setContentIntent(openAppIntent)
        .setOnlyAlertOnce(true)
        .setLocalOnly(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    private fun postListening(content: ListeningContent) {
        markShown(content)
        notify(null, LISTENING_ID, listeningNotification(content))
    }

    private fun markShown(content: ListeningContent) {
        shown = content
        lastPostAtMs = SystemClock.elapsedRealtime()
    }

    private fun dropPending() {
        mainHandler.removeCallbacks(flushPending)
        pending = null
    }

    /** Posts only when the user allows notifications; the service and in-app UI work either way. */
    private fun notify(tag: String?, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!manager.areNotificationsEnabled()) return
        manager.notify(tag, id, notification)
    }

    private fun broadcast(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode, NotificationActionReceiver.intent(context, action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun confirmIntent(id: Long, approved: Boolean): PendingIntent {
        val intent = NotificationActionReceiver.intent(context, NotificationActionReceiver.ACTION_CONFIRM)
            .putExtra(NotificationActionReceiver.EXTRA_CONFIRM_ID, id)
            .putExtra(NotificationActionReceiver.EXTRA_APPROVED, approved)
        return PendingIntent.getBroadcast(
            context, confirmationRequestCode(id, approved), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Buttons on task notifications, each answered by [NotificationActionReceiver]. */
    internal enum class TaskAction(val intentAction: String) {
        RunNow(NotificationActionReceiver.ACTION_TASK_RUN_NOW),
        Snooze(NotificationActionReceiver.ACTION_TASK_SNOOZE),
        Cancel(NotificationActionReceiver.ACTION_TASK_CANCEL),
        Dismiss(NotificationActionReceiver.ACTION_TASK_DISMISS),
    }

    companion object {
        const val LISTENING_ID = 1001
        private const val CONFIRM_ID = 1002
        private const val TASK_ID = 1003
        const val TASK_RUN_ID = 1004
        private const val CHANNEL_LISTENING = "listening"
        private const val CHANNEL_CONFIRM = "confirm"
        private const val CHANNEL_REMINDERS = "reminders"
        private const val CHANNEL_ALARMS = "alarms"
        private const val CHANNEL_TASKS = "tasks"

        private const val MIN_UPDATE_INTERVAL_MS = 500L
        /** Matches the controller's confirmation timeout, after which the request counts as denied. */
        private const val CONFIRM_TIMEOUT_MS = 60_000L
        /** An alarm nobody stops rings for this long. */
        private const val ALARM_RING_MS = 10 * 60_000L
        private const val MAX_DETAIL_CHARS = 1_000

        private const val REQUEST_OPEN_APP = 1
        private const val REQUEST_CANCEL_TASK = 2
        private const val REQUEST_TURN_OFF = 3
        private const val REQUEST_CONFIRM_BASE = 1_000
        private const val CONFIRM_CODE_SPAN = 1_000_000L
        private const val REQUEST_TASK_BASE = 3_000_000
        private const val TASK_CODE_SPAN = 250_000L

        /**
         * A PendingIntent request code unique to each confirmation and answer (extras do not make
         * PendingIntents distinct), kept clear of the fixed request codes above.
         */
        internal fun confirmationRequestCode(id: Long, approved: Boolean): Int =
            REQUEST_CONFIRM_BASE + (id.mod(CONFIRM_CODE_SPAN) * 2 + if (approved) 1 else 0).toInt()

        /** Like [confirmationRequestCode], for task buttons; above every confirmation code. */
        internal fun taskRequestCode(id: Long, action: TaskAction): Int =
            REQUEST_TASK_BASE + (id.mod(TASK_CODE_SPAN) * TaskAction.entries.size + action.ordinal).toInt()

        internal fun confirmationTag(id: Long) = "confirm:$id"

        internal fun taskTag(id: Long) = "task:$id"

        /** How long to hold an update so posts stay [intervalMs] apart; 0 means post now. */
        internal fun throttleDelayMs(lastPostAtMs: Long?, nowMs: Long, intervalMs: Long): Long =
            if (lastPostAtMs == null) 0 else (lastPostAtMs + intervalMs - nowMs).coerceIn(0, intervalMs)
    }
}
