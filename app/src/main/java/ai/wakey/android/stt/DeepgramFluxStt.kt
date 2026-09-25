package ai.wakey.android.stt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException

/**
 * Deepgram Flux streaming STT over `wss://api.deepgram.com/v2/listen` (model `flux-general-multi`,
 * `language_hint=en&language_hint=hi`).
 *
 * Each [open] is one WebSocket for one utterance: PCM16 is streamed in 80 ms frames, Flux turn
 * events become partial transcripts, and the first non-blank `EndOfTurn` becomes the single final
 * transcript, after which the stream closes (see [FluxSession] for the full lifecycle). The key is
 * read from [apiKey] at every [open], so a key saved in Settings applies to the next utterance.
 */
class DeepgramFluxStt internal constructor(
    private val http: OkHttpClient,
    private val apiKey: () -> String?,
    private val endpoint: HttpUrl,
    private val turns: FluxTurnTuning = FluxTurnTuning(),
) : SpeechToText {
    constructor(http: OkHttpClient, apiKey: () -> String?) : this(http, apiKey, LISTEN_ENDPOINT)

    /** Never throws: a missing key or network failure is reported through [SttListener.onError]. */
    override fun open(config: SttConfig, listener: SttListener): SttSession {
        val session = FluxSession(config, listener, Dispatchers.Default.limitedParallelism(1), System::nanoTime)
        val key = currentKey()
        if (key == null) {
            session.startFailed(SttError(SttError.Kind.MissingKey, "No Deepgram API key saved"))
        } else {
            val request = listenRequest(config, key)
            session.start { socketListener -> connect(request, socketListener) }
        }
        return session
    }

    /**
     * Small authenticated check used by Settings → Test Deepgram: opens a Flux stream, waits for
     * Deepgram's `Connected` message and closes it again without sending audio.
     *
     * @return e.g. "Deepgram OK · connected in 420 ms".
     * @throws IllegalStateException with a readable message if the key is missing or rejected, or
     *   Deepgram cannot be reached.
     */
    suspend fun testConnection(): String {
        val key = currentKey() ?: error("Add a Deepgram API key first")
        val request = listenRequest(SttConfig(model = TEST_MODEL, languageHints = listOf("en", "hi")), key)
        val connected = CompletableDeferred<Long>()
        val startedAt = System.nanoTime()
        val socket = connect(request, object : FluxSocket.Listener {
            override fun onOpen() {}

            override fun onText(text: String) {
                if (runCatching { FluxMessage.parse(text) }.getOrNull() == FluxMessage.Connected) {
                    connected.complete((System.nanoTime() - startedAt) / 1_000_000)
                }
            }

            override fun onClosed(code: Int, reason: String) {
                connected.completeExceptionally(IllegalStateException("Deepgram closed the connection (code $code)"))
            }

            override fun onFailure(error: SttError) {
                connected.completeExceptionally(IllegalStateException(error.message))
            }
        })
        val connectMs = try {
            withTimeoutOrNull(TEST_TIMEOUT_MS) { connected.await() }
        } catch (e: Exception) {
            socket.cancel()
            throw e
        }
        if (connectMs == null) {
            socket.cancel()
            error("Deepgram did not answer within ${TEST_TIMEOUT_MS / 1000} s")
        }
        socket.sendText(CLOSE_STREAM)
        socket.close()
        return "Deepgram OK · connected in $connectMs ms"
    }

    private fun currentKey(): String? = runCatching(apiKey).getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun listenRequest(config: SttConfig, key: String): Request = Request.Builder()
        .url(fluxListenUrl(endpoint, config, turns))
        .header("Authorization", "Token $key")
        .build()

    private fun connect(request: Request, listener: FluxSocket.Listener): FluxSocket {
        val webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
                listener.onClosed(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (t is EOFException && response == null) {
                    // The server ended the TCP stream without a close frame.
                    listener.onClosed(ABNORMAL_CLOSURE, "")
                } else {
                    listener.onFailure(fluxFailure(t, response?.code, response?.header("dg-error")))
                }
            }
        })
        return object : FluxSocket {
            override fun sendBinary(bytes: ByteArray) {
                webSocket.send(bytes.toByteString())
            }

            override fun sendText(text: String) {
                webSocket.send(text)
            }

            override fun close() {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun cancel() = webSocket.cancel()
        }
    }

    internal companion object {
        /** OkHttp upgrades `https` to `wss`; [HttpUrl] cannot hold a `wss` URL itself. */
        val LISTEN_ENDPOINT = "https://api.deepgram.com/v2/listen".toHttpUrl()

        /**
         * End-of-turn tuning for spoken commands, measured on 54 Indian-English and Hinglish command
         * clips with "Hey Wakey," in front (short, long, sped up, and with a 0.8–1.2 s pause
         * mid-sentence), plus 30 noisy/far-field versions, streamed live:
         * - `eot_threshold` 0.85 instead of 0.7: 0.7 ended 5 of 54 commands at a pause ("Set an
         *   alarm." for "set an alarm for 6:30", "Settings main." for "Settings mein Bluetooth on
         *   karo"), and the cut transcripts were also worse overall (command WER 29.6% → 20.0%,
         *   key words right 77% → 84%). No cut-offs at 0.85. It costs ~0.5 s: speech end → final
         *   1.28 s median (0.74 s before), 1.77 s p90.
         * - `eot_timeout_ms` 1500: ends a turn 1.5 s after real silence even when the model is
         *   unsure (mumbled or code-mixed speech).
         * - In background chatter neither fires, so [FluxSession] ends a heard request by hand.
         * Nova-3 (multi, en-IN, hi) was also measured: similar accuracy, but its silence endpointing
         * cut commands at pauses.
         */
        const val EOT_THRESHOLD = "0.85"
        const val EOT_TIMEOUT_MS = 1_500

        /**
         * Flux sends `EagerEndOfTurn` once it is this sure the turn is over, before `EndOfTurn` at
         * [EOT_THRESHOLD], and `TurnResumed` if the user goes on. At the first, Wakey shows it is
         * working and starts the agent, which reads the screen and asks the model but acts only once
         * `EndOfTurn` confirms the same words; at the second it drops that run and listens on. Only
         * `EndOfTurn` ends the turn, so nothing is cut short. Deepgram suggests 0.4 with a 0.7
         * end-of-turn threshold; it must not be above [EOT_THRESHOLD].
         */
        const val EAGER_EOT_THRESHOLD = "0.6"

        private const val TEST_MODEL = "flux-general-multi"
        private const val TEST_TIMEOUT_MS = 10_000L
        private const val NORMAL_CLOSURE = 1000
        private const val ABNORMAL_CLOSURE = 1006
    }
}

/** Flux end-of-turn parameters; the defaults are the tuned values documented on [DeepgramFluxStt]. */
internal data class FluxTurnTuning(
    val eotThreshold: String = DeepgramFluxStt.EOT_THRESHOLD,
    val eotTimeoutMs: Int = DeepgramFluxStt.EOT_TIMEOUT_MS,
    /** Null leaves eager end-of-turn off. */
    val eagerEotThreshold: String? = DeepgramFluxStt.EAGER_EOT_THRESHOLD,
)

/**
 * The Flux listen URL for [config]. `language_hint` and `keyterm` are repeated once per value;
 * hints are sent only to multilingual models because Flux rejects them elsewhere with HTTP 400.
 *
 * On the command clips behind [DeepgramFluxStt.EOT_THRESHOLD], hints en+hi transcribed the same as
 * no hints, while en alone turned Hinglish verbs into English words ("Torch jalao" → "George
 * July"); keyterms left `flux-general-multi` transcripts unchanged, word for word.
 */
internal fun fluxListenUrl(endpoint: HttpUrl, config: SttConfig, turns: FluxTurnTuning = FluxTurnTuning()): HttpUrl =
    endpoint.newBuilder().apply {
        addQueryParameter("model", config.model)
        addQueryParameter("encoding", "linear16")
        addQueryParameter("sample_rate", config.sampleRate.toString())
        addQueryParameter("eot_threshold", turns.eotThreshold)
        addQueryParameter("eot_timeout_ms", turns.eotTimeoutMs.toString())
        turns.eagerEotThreshold?.let { addQueryParameter("eager_eot_threshold", it) }
        if ("-multi" in config.model) {
            config.languageHints.cleaned().forEach { addQueryParameter("language_hint", it) }
        }
        config.keyterms.cleaned().forEach { addQueryParameter("keyterm", it) }
    }.build()

private fun List<String>.cleaned(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Maps a WebSocket failure; [httpCode] and [detail] (`dg-error` header) come from a rejected upgrade. */
internal fun fluxFailure(t: Throwable, httpCode: Int?, detail: String?): SttError = when {
    httpCode == 401 || httpCode == 403 ->
        SttError(SttError.Kind.Auth, "Deepgram rejected the API key (HTTP $httpCode)")
    httpCode != null && httpCode != 101 ->
        SttError(SttError.Kind.Server, "Deepgram HTTP $httpCode" + detail?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
    t is ProtocolException -> SttError(SttError.Kind.Protocol, t.message ?: "WebSocket protocol error")
    t is IOException -> SttError(SttError.Kind.Network, "Could not reach Deepgram: ${t.message ?: t.javaClass.simpleName}")
    else -> SttError(SttError.Kind.Protocol, t.message ?: t.javaClass.simpleName)
}
