package ai.wakey.android.service

import ai.wakey.android.WakeyApp
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Microphone foreground service. Started only from the visible app via [start] (or the floating
 * button). It runs while the wake word or the floating button is on: only a running microphone
 * service may record while Wakey is in the background. Keeps on-device wake listening alive with the
 * screen off (a partial wake lock, held only while the wake word is on) and shows the listening
 * notification with working Stop and Turn off actions.
 *
 * Sticky: if Android kills it, it restarts when it may. A background restart may start the
 * microphone only while Wakey is the default assistant (Android's exemption for apps providing the
 * VoiceInteractionService); otherwise it stops itself and comes back the next time Wakey is opened.
 */
class WakeService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when {
            intent?.action == ACTION_STOP -> {
                WakeyApp.graph.controller.stop(silent = true)
                shutDown()
            }
            // Already listening: a second start must not open the microphone again. Entering the
            // foreground again answers this start (Android expects it after startForegroundService)
            // and, while Wakey is visible, renews the service's microphone access for the background.
            isRunning -> refreshForeground()
            else -> startListening()
        }
        return START_STICKY
    }

    private fun startListening() {
        if (!enterForeground()) {
            stopSelf()
            return
        }
        val controller = WakeyApp.graph.controller
        if (!controller.onWakeServiceStarted()) {
            shutDown()
            return
        }
        // The controller refreshes the notification on phase changes; this also carries the live
        // transcript and status line. Notifications skips unchanged content.
        val notifications = WakeyApp.graph.notifications
        lifecycleScope.launch {
            controller.state.collect { state ->
                notifications.updateListening(state)
                // The detector must keep running with the screen off; tap-to-talk alone needs no wake lock.
                if (state.wakeWordEnabled) acquireWakeLock() else releaseWakeLock()
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        releaseWakeLock()
        WakeyApp.graph.controller.onWakeServiceStopped()
        super.onDestroy()
    }

    /** Must run first: Android requires startForeground soon after startForegroundService. */
    private fun enterForeground(): Boolean {
        val notification = WakeyApp.graph.notifications.buildListening(WakeyApp.graph.controller.state.value)
        return try {
            ServiceCompat.startForeground(
                this, Notifications.LISTENING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            isRunning = true
            true
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException (started while in the background).
            _startProblem.value = startFailureMessage(e)
            false
        } catch (e: SecurityException) {
            // Android 14+: a microphone service needs RECORD_AUDIO at the moment it starts.
            _startProblem.value = startFailureMessage(e)
            false
        }
    }

    private fun refreshForeground() {
        val notification = WakeyApp.graph.notifications.buildListening(WakeyApp.graph.controller.state.value)
        try {
            ServiceCompat.startForeground(
                this, Notifications.LISTENING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: IllegalStateException) {
            // Still in the foreground from the first start; the existing access stays as it was.
            Log.w(TAG, "Could not renew the foreground service", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not renew the foreground service", e)
        }
    }

    private fun shutDown() {
        // Cleared first so no late notification update re-posts the notification being removed.
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Held for exactly as long as the wake word is on; released when it goes off and in onDestroy.
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        /** Stops listening and the service. Deliver with `startService`, never `startForegroundService`. */
        const val ACTION_STOP = "ai.wakey.android.action.STOP_LISTENING"
        private const val WAKE_LOCK_TAG = "Wakey:wake"
        private const val TAG = "WakeService"
        internal const val MIC_PERMISSION_MESSAGE = "Allow microphone access so Wakey can listen for the wake word."
        internal const val BACKGROUND_START_MESSAGE =
            "Android blocked wake listening from starting in the background. Open Wakey and turn it on again."

        /** True while the service is in the foreground. Main thread only. */
        internal var isRunning = false
            private set

        private val _startProblem = MutableStateFlow<String?>(null)

        /**
         * Why the last [start] could not bring the service up, or null. The service cannot set the
         * controller's status line itself, so the UI or controller should surface this.
         * Failures after the service is up are reported by `onWakeServiceStarted` instead.
         */
        val startProblem: StateFlow<String?> = _startProblem.asStateFlow()

        /** Starts wake listening. Call only while Wakey is visible, after RECORD_AUDIO is granted. */
        fun start(context: Context) {
            _startProblem.value = null
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                _startProblem.value = MIC_PERMISSION_MESSAGE
                return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeService::class.java))
            } catch (e: IllegalStateException) {
                _startProblem.value = startFailureMessage(e)
            }
        }

        /** Stops the service; [onDestroy] tells the controller. Safe to call when not running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, WakeService::class.java))
        }

        internal fun startFailureMessage(error: Exception): String =
            if (error is SecurityException) MIC_PERMISSION_MESSAGE else BACKGROUND_START_MESSAGE
    }
}
