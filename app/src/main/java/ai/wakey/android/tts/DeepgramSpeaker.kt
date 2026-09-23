package ai.wakey.android.tts

import ai.wakey.android.config.TtsEngine
import ai.wakey.android.config.WakeySettings
import okhttp3.OkHttpClient

/** Deepgram Flux TTS (`/v2/speak`, raw linear16 into AudioTrack). STUB: implemented by the speech module. */
class DeepgramSpeaker(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val settings: () -> WakeySettings,
) : SpeechOutput {
    override val engine = TtsEngine.Deepgram
    override suspend fun speak(text: String, languageTag: String?, onStart: () -> Unit) {}
    override fun stop() {}
    override fun release() {}

    companion object {
        val VOICES: List<VoiceOption> = emptyList()
    }
}
