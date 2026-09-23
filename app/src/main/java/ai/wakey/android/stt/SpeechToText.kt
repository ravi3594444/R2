package ai.wakey.android.stt

/**
 * Streaming speech-to-text. Implementations own their network transport and credentials
 * lookup so they can later be swapped for a backend-proxied provider.
 */
interface SpeechToText {
    /** Opens a streaming session. Audio pushed before the connection is ready is buffered. */
    fun open(config: SttConfig, listener: SttListener): SttSession
}

data class SttConfig(
    /** e.g. `flux-general-multi`. */
    val model: String,
    /** Deepgram `language_hint` values, e.g. ["en", "hi"]. Empty = auto-detect. */
    val languageHints: List<String>,
    val sampleRate: Int = 16_000,
    /** Words to bias recognition toward (app names, the wake phrase). */
    val keyterms: List<String> = emptyList(),
)

interface SttSession {
    /** Thread-safe. 16-bit mono PCM at [SttConfig.sampleRate]. */
    fun sendPcm(samples: ShortArray, length: Int = samples.size)

    /** Push-to-talk release: finalise the current turn now instead of waiting for end-of-turn. */
    fun endTurn()

    /** Graceful close; a pending final transcript may still be delivered. */
    fun close()

    /** Immediate abort. No further listener callbacks. Idempotent. */
    fun cancel()
}

interface SttListener {
    fun onConnected(connectMs: Long) {}
    fun onSpeechStarted() {}

    /**
     * [isFinal] is true exactly once per session, for the end-of-turn transcript. After a final
     * transcript the session closes its stream by itself. [transcriptionMs] is the time from the
     * last recognised word to the final result, when measurable.
     */
    fun onTranscript(text: String, isFinal: Boolean, languages: List<String>, transcriptionMs: Long? = null)

    fun onError(error: SttError)
    fun onClosed() {}
}

data class SttError(val kind: Kind, val message: String) {
    enum class Kind { MissingKey, Auth, Network, Server, Protocol }
}
