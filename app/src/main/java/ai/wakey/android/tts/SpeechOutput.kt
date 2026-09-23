package ai.wakey.android.tts

import ai.wakey.android.config.TtsEngine

/**
 * A replaceable text-to-speech engine. [speak] suspends until playback finishes; cancelling the
 * calling coroutine (or calling [stop]) must silence audio immediately.
 */
interface SpeechOutput {
    val engine: TtsEngine

    /**
     * [languageTag] is a BCP-47 hint such as "en-IN" or "hi-IN"; null = infer from text.
     * [onStart] is invoked once, when audio actually begins playing (used for latency timing).
     */
    suspend fun speak(text: String, languageTag: String?, onStart: () -> Unit = {})

    fun stop()
    fun release()
}

data class VoiceOption(
    val id: String,
    val label: String,
    val languageTag: String,
    val offline: Boolean,
    val engine: TtsEngine,
)

/** Thrown by an engine that cannot speak right now (no network, no key, unsupported language). */
class SpeechOutputException(message: String, cause: Throwable? = null) : Exception(message, cause)
