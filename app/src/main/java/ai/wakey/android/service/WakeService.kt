package ai.wakey.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Microphone foreground service. Started only from the visible app. Keeps wake listening alive
 * with the screen off, shows a persistent notification with a working Stop action.
 * STUB: implemented by the wake-word/service module.
 */
class WakeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "ai.wakey.android.action.STOP_LISTENING"
        fun start(context: Context) {}
        fun stop(context: Context) {}
    }
}
