package ai.wakey.android.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Transient, may-duck audio focus for spoken replies, shared by both engines. Holds nest, so a Deepgram reply that
 * falls back to Android keeps focus throughout; losing focus to a call or another assistant stops every holder.
 */
internal class SpeechAudioFocus(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val holders = mutableListOf<Holder>()
    private var request: AudioFocusRequest? = null

    private class Holder(val onLoss: () -> Unit)

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            synchronized(this) { holders.toList() }.forEach { it.onLoss() }
        }
    }

    /** Runs [block] while holding focus; [onLoss] is called if another app takes focus meanwhile. */
    suspend fun <T> hold(onLoss: () -> Unit, block: suspend () -> T): T {
        val holder = Holder(onLoss)
        acquire(holder)
        try {
            return block()
        } finally {
            release(holder)
        }
    }

    @Synchronized
    private fun acquire(holder: Holder) {
        holders += holder
        if (request != null) return
        val newRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(SPEECH_ATTRIBUTES)
            .setOnAudioFocusChangeListener(listener)
            .build()
        // Speech goes ahead even if focus is refused: the request only asks other audio to duck.
        audioManager?.requestAudioFocus(newRequest)
        request = newRequest
    }

    @Synchronized
    private fun release(holder: Holder) {
        holders -= holder
        if (holders.isNotEmpty()) return
        request?.let { audioManager?.abandonAudioFocusRequest(it) }
        request = null
    }

    companion object {
        /** Attributes of every spoken reply, on both engines. */
        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
