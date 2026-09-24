package ai.wakey.android.assist

import ai.wakey.android.WakeyApp
import android.service.voice.VoiceInteractionService

/**
 * Present while Wakey is the default digital assistant. Android binds it at boot and whenever the
 * app's process was killed, and an app providing the active VoiceInteractionService may start a
 * microphone service from the background. That is how "Hey Wakey" comes back by itself after a
 * reboot or after the app was swiped away, the way Siri keeps listening.
 */
class WakeyVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        WakeyApp.graph.controller.restoreWakeListening(this)
    }
}
