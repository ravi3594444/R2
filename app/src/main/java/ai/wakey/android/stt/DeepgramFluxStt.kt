package ai.wakey.android.stt

import okhttp3.OkHttpClient

/**
 * Deepgram Flux streaming STT over `wss://api.deepgram.com/v2/listen` (model `flux-general-multi`,
 * `language_hint=en&language_hint=hi`). STUB: implemented by the speech module.
 */
class DeepgramFluxStt(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
) : SpeechToText {
    override fun open(config: SttConfig, listener: SttListener): SttSession = throw UnsupportedOperationException()

    /** Small authenticated check used by Settings → Test Deepgram. */
    suspend fun testConnection(): String = throw UnsupportedOperationException()
}
