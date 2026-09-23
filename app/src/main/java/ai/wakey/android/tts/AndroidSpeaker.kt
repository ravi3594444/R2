package ai.wakey.android.tts

import ai.wakey.android.config.TtsEngine
import ai.wakey.android.config.WakeySettings
import android.content.Context

/** Android TextToSpeech, preferring offline voices. STUB: implemented by the speech module. */
class AndroidSpeaker(private val context: Context, private val settings: () -> WakeySettings) : SpeechOutput {
    override val engine = TtsEngine.Android
    override suspend fun speak(text: String, languageTag: String?, onStart: () -> Unit) {}
    override fun stop() {}
    override fun release() {}

    /** Installed voices for English (India/US/UK) and Hindi, offline first. */
    fun availableVoices(): List<VoiceOption> = emptyList()
}
