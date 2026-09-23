package ai.wakey.android.service

import ai.wakey.android.R
import ai.wakey.android.WakeyApp
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.ui.MainActivity
import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Notification channels, the persistent listening notification (with Stop and Turn off), and
 * heads-up confirmation prompts with Approve / Deny actions for when Wakey is behind another app.
 *
 * Both notifications are private on a secure lock screen: the public version shows only the
 * phase, never speech, replies or the pending question. Call from the main thread only.
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

    /** Creates the "listening" (quiet, persistent) and "confirm" (heads-up) channels. Idempotent. */
    fun ensureChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_LISTENING, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Listening")
                    .setDescription("Shown while Wakey listens for the wake word, with Stop and Turn off buttons.")
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_CONFIRM, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Approvals")
                    .setDescription("Asks before Wakey sends, buys or changes something for you.")
                    // Wakey usually speaks the question aloud; a chime would talk over it.
                    .setSound(null, null)
                    .setVibrationEnabled(true)
                    .build(),
            ),
        )
    }

    /**
     * Builds the listening notification that [WakeService] passes to `startForeground`. The caller
     * posts it immediately, so it also becomes the baseline [updateListening] compares against.
     */
    fun buildListening(state: AssistantUiState): Notification {
        val content = listeningContent(state, wakePhrase())
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
        val content = listeningContent(state, wakePhrase())
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

    companion object {
        const val LISTENING_ID = 1001
        private const val CONFIRM_ID = 1002
        private const val CHANNEL_LISTENING = "listening"
        private const val CHANNEL_CONFIRM = "confirm"

        private const val MIN_UPDATE_INTERVAL_MS = 500L
        /** Matches the controller's confirmation timeout, after which the request counts as denied. */
        private const val CONFIRM_TIMEOUT_MS = 60_000L
        private const val MAX_DETAIL_CHARS = 1_000

        private const val REQUEST_OPEN_APP = 1
        private const val REQUEST_CANCEL_TASK = 2
        private const val REQUEST_TURN_OFF = 3
        private const val REQUEST_CONFIRM_BASE = 1_000
        private const val CONFIRM_CODE_SPAN = 1_000_000L

        /**
         * A PendingIntent request code unique to each confirmation and answer (extras do not make
         * PendingIntents distinct), kept clear of the fixed request codes above.
         */
        internal fun confirmationRequestCode(id: Long, approved: Boolean): Int =
            REQUEST_CONFIRM_BASE + (id.mod(CONFIRM_CODE_SPAN) * 2 + if (approved) 1 else 0).toInt()

        internal fun confirmationTag(id: Long) = "confirm:$id"

        /** How long to hold an update so posts stay [intervalMs] apart; 0 means post now. */
        internal fun throttleDelayMs(lastPostAtMs: Long?, nowMs: Long, intervalMs: Long): Long =
            if (lastPostAtMs == null) 0 else (lastPostAtMs + intervalMs - nowMs).coerceIn(0, intervalMs)
    }
}
