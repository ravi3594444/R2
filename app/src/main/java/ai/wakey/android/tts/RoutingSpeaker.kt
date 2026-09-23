package ai.wakey.android.tts

import ai.wakey.android.config.TtsEngine
import ai.wakey.android.config.WakeySettings

/**
 * Speaks with the engine chosen in settings. Deepgram has no Hindi voice, so Devanagari text is
 * routed to Android TTS; Deepgram failures (no network / key) fall back to Android.
 * STUB: implemented by the speech module.
 */
class RoutingSpeaker(
    val android: AndroidSpeaker,
    val deepgram: DeepgramSpeaker,
    private val settings: () -> WakeySettings,
) : SpeechOutput {
    override val engine: TtsEngine get() = settings().ttsEngine
    override suspend fun speak(text: String, languageTag: String?, onStart: () -> Unit) {}
    override fun stop() {}
    override fun release() {}
}
