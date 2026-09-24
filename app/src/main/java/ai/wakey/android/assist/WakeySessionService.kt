package ai.wakey.android.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Creates the session shown by the assistant gesture (long-press power or home, corner swipe). */
class WakeySessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = WakeySession(this)
}
