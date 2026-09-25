package ai.wakey.android.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI-compatible `/chat/completions` client (Fireworks by default) with tools and image input.
 *
 * Endpoint, model and key are read through lambdas on every request so Settings changes apply
 * immediately. Each request is bounded by [callTimeoutMs], retried once on 429/5xx, and cancelled
 * on the wire when the calling coroutine is cancelled. Nothing about the request is logged.
 */
class OpenAiCompatibleChatModel internal constructor(
    http: OkHttpClient,
    private val baseUrl: () -> String,
    private val model: () -> String,
    private val apiKey: () -> String?,
    callTimeoutMs: Long,
    private val retryDelayMs: Long,
) : ChatModel {

    constructor(
        http: OkHttpClient,
        baseUrl: () -> String,
        model: () -> String,
        apiKey: () -> String?,
    ) : this(http, baseUrl, model, apiKey, CALL_TIMEOUT_MS, RETRY_DELAY_MS)

    private val client = http.newBuilder().callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()
    private val warmer = ConnectionWarmer(client)

    /** Endpoint + model pairs that rejected `reasoning_effort`; later requests to them omit it. */
    private val noReasoningEffort: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun complete(request: ChatRequest): ChatResponse {
        val key = apiKey()?.trim().orEmpty()
        if (key.isEmpty()) throw LlmException(LlmException.Kind.MissingKey, "Add your LLM API key in Settings.")
        val url = completionsUrl()
        val modelId = model().trim()
        val endpoint = "$url|$modelId"
        val started = System.nanoTime()
        val withEffort = endpoint !in noReasoningEffort
        val body = try {
            post(url, key, request.sessionId, encode(request, modelId, REASONING_EFFORT.takeIf { withEffort }))
        } catch (e: LlmException) {
            if (!withEffort || e.kind != LlmException.Kind.BadRequest) throw e
            // Not every model accepts reasoning_effort: retry once without it and remember the answer.
            post(url, key, request.sessionId, encode(request, modelId, null)).also { noReasoningEffort += endpoint }
        }
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        return withContext(Dispatchers.Default) { ChatJson.parseResponse(body, latencyMs) }
    }

    override fun warmUp() {
        val key = apiKey()?.trim().orEmpty()
        if (key.isEmpty()) return
        warmer.warm((baseUrl().trim().trimEnd('/') + "/models").toHttpUrlOrNull(), mapOf("Authorization" to "Bearer $key"))
    }

    override suspend fun testConnection(): String {
        val response = complete(
            ChatRequest(listOf(ChatMessage.User("Reply with the single word OK.")), maxTokens = 8, temperature = 0.0),
        )
        return "OK · ${model().trim().substringAfterLast('/')} replied in ${response.latencyMs} ms"
    }

    private fun completionsUrl(): HttpUrl =
        (baseUrl().trim().trimEnd('/') + "/chat/completions").toHttpUrlOrNull()
            ?: throw LlmException(LlmException.Kind.BadRequest, "The LLM base URL in Settings is not a valid http(s) URL.")

    // Screenshots make bodies large, so JSON is built off the caller's (usually main) thread.
    private suspend fun encode(request: ChatRequest, modelId: String, reasoningEffort: String?): String =
        withContext(Dispatchers.Default) { ChatJson.requestBody(request, modelId, reasoningEffort).toString() }

    /** POSTs [json] and returns the body of a 2xx response, retrying once on 429/5xx. */
    private suspend fun post(url: HttpUrl, key: String, sessionId: String?, json: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            // Fireworks caches prompt prefixes per replica; this keeps an agent's calls on one replica.
            .apply { sessionId?.let { header("x-session-affinity", it) } }
            .post(json.toRequestBody(JSON))
            .build()
        var retried = false
        while (true) {
            val reply = execute(request)
            if (reply.code in 200..299) return reply.body
            val retryable = reply.code == 429 || reply.code >= 500
            if (!retryable || retried) throw httpError(reply)
            retried = true
            delay(if (reply.code == 429) rateLimitDelay(reply.retryAfter) else retryDelayMs)
        }
    }

    private fun rateLimitDelay(retryAfter: String?): Long =
        retryAfter?.trim()?.toLongOrNull()?.times(1_000)?.coerceIn(retryDelayMs, MAX_RETRY_AFTER_MS) ?: (retryDelayMs * 2)

    private suspend fun execute(request: Request): HttpReply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(networkError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use { HttpReply(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) }
                } catch (e: IOException) {
                    continuation.resumeWithException(networkError(e))
                    return
                }
                continuation.resume(reply)
            }
        })
    }

    private fun networkError(e: IOException): LlmException = LlmException(
        LlmException.Kind.Network,
        when (e) {
            is UnknownHostException -> "Can't reach the AI service. Check your internet connection."
            is InterruptedIOException -> "The AI service took too long to answer."
            else -> "Network problem talking to the AI service."
        },
        e,
    )

    private fun httpError(reply: HttpReply): LlmException {
        val detail = ChatJson.errorMessage(reply.body)
        val suffix = detail?.let { ": $it" }.orEmpty()
        return when (reply.code) {
            401, 403 -> LlmException(LlmException.Kind.Auth, "The AI service rejected the API key (HTTP ${reply.code})$suffix")
            429 -> LlmException(LlmException.Kind.RateLimit, "The AI service is rate limiting requests (HTTP 429)$suffix")
            in 500..599 -> LlmException(LlmException.Kind.Server, "The AI service had an error (HTTP ${reply.code})$suffix")
            else -> LlmException(LlmException.Kind.BadRequest, "The AI service refused the request (HTTP ${reply.code})$suffix")
        }
    }

    private class HttpReply(val code: Int, val body: String, val retryAfter: String?)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val CALL_TIMEOUT_MS = 30_000L
        const val RETRY_DELAY_MS = 600L
        const val MAX_RETRY_AFTER_MS = 3_000L

        /** Measured on the default Fireworks model's tool calls: ~0.8 s instead of ~3 s, no reasoning tokens. */
        const val REASONING_EFFORT = "none"
    }
}
