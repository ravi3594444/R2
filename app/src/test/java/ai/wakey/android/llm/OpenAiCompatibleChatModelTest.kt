package ai.wakey.android.llm

import ai.wakey.android.llm.FakeHttpServer.Reply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket

class OpenAiCompatibleChatModelTest {
    private val server = FakeHttpServer()
    private val replies get() = server.replies
    private val received get() = server.received.toList()
    private val http = OkHttpClient()

    @After
    fun stop() = server.close()

    private fun model(key: String? = "test-key", callTimeoutMs: Long = 5_000) = OpenAiCompatibleChatModel(
        http,
        baseUrl = { "http://127.0.0.1:${server.port}/v1/" },
        model = { "accounts/test/models/tiny" },
        apiKey = { key },
        callTimeoutMs = callTimeoutMs,
        retryDelayMs = 10,
    )

    private val request = ChatRequest(listOf(ChatMessage.User("open YouTube")))

    private fun ok(text: String = "OK") =
        Reply(200, """{"choices":[{"message":{"content":"$text"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":2}}""")

    private fun expectFailure(kind: LlmException.Kind, block: suspend () -> Unit): LlmException = runBlocking {
        try {
            block()
        } catch (e: LlmException) {
            assertEquals(e.message, kind, e.kind)
            return@runBlocking e
        }
        fail("Expected $kind")
        throw AssertionError()
    }

    @Test
    fun postsToChatCompletionsWithBearerKeyAndReasoningEffort() = runBlocking {
        replies += ok("Hello")
        val response = model().complete(request)

        assertEquals("Hello", response.text)
        assertEquals(12, response.promptTokens)
        assertEquals(2, response.completionTokens)
        val sent = received.single()
        assertEquals("/v1/chat/completions", sent.path)
        assertEquals("Bearer test-key", sent.headers["authorization"])
        val body = JSONObject(sent.body)
        assertEquals("accounts/test/models/tiny", body.getString("model"))
        assertEquals("none", body.getString("reasoning_effort"))
    }

    @Test
    fun retriesOnceWithoutReasoningEffortAfterA400AndRemembers() = runBlocking {
        replies += Reply(400, """{"error":{"message":"reasoning_effort is not supported"}}""")
        replies += ok()
        replies += ok()
        val model = model()
        model.complete(request)
        model.complete(request)

        val bodies = received.map { JSONObject(it.body) }
        assertEquals(3, bodies.size)
        assertTrue(bodies[0].has("reasoning_effort"))
        assertFalse(bodies[1].has("reasoning_effort"))
        assertFalse(bodies[2].has("reasoning_effort"))
    }

    @Test
    fun badRequestCarriesTheProviderMessage() {
        replies += Reply(400, """{"error":{"message":"Image too large"}}""")
        replies += Reply(400, """{"error":{"message":"Image too large"}}""")
        val error = expectFailure(LlmException.Kind.BadRequest) { model().complete(request) }
        assertTrue(error.message!!.contains("HTTP 400"))
        assertTrue(error.message!!.contains("Image too large"))
    }

    @Test
    fun authErrorsAreNotRetried() {
        replies += Reply(401, """{"error":{"message":"Unauthorized"}}""")
        expectFailure(LlmException.Kind.Auth) { model().complete(request) }
        assertEquals(1, received.size)
        replies += Reply(403, "Forbidden")
        expectFailure(LlmException.Kind.Auth) { model().complete(request) }
    }

    @Test
    fun rateLimitIsRetriedOnce() {
        replies += Reply(429, """{"error":{"message":"slow down"}}""")
        replies += ok("after retry")
        assertEquals("after retry", runBlocking { model().complete(request) }.text)
        assertEquals(2, received.size)

        replies += Reply(429, "slow down")
        replies += Reply(429, "slow down")
        expectFailure(LlmException.Kind.RateLimit) { model().complete(request) }
        assertEquals(4, received.size)
    }

    @Test
    fun serverErrorsAreRetriedOnceThenReported() {
        replies += Reply(502, "Bad gateway")
        replies += Reply(503, "Unavailable")
        val error = expectFailure(LlmException.Kind.Server) { model().complete(request) }
        assertTrue(error.message!!.contains("HTTP 503"))
        assertEquals(2, received.size)
    }

    @Test
    fun blankKeyFailsWithoutARequest() {
        expectFailure(LlmException.Kind.MissingKey) { model(key = "  ").complete(request) }
        expectFailure(LlmException.Kind.MissingKey) { model(key = null).complete(request) }
        assertTrue(received.isEmpty())
    }

    @Test
    fun unparsableSuccessIsABadResponse() {
        replies += Reply(200, "<html>captive portal</html>")
        expectFailure(LlmException.Kind.BadResponse) { model().complete(request) }
    }

    @Test
    fun networkFailuresAndTimeouts() {
        replies += Reply(200, "{}", delayMs = 1_500)
        val timeout = expectFailure(LlmException.Kind.Network) { model(callTimeoutMs = 300).complete(request) }
        assertTrue(timeout.message!!.contains("too long"))

        val closed = ServerSocket(0).use { it.localPort }
        val unreachable = OpenAiCompatibleChatModel(http, { "http://127.0.0.1:$closed/v1" }, { "m" }, { "k" })
        expectFailure(LlmException.Kind.Network) { unreachable.complete(request) }
    }

    @Test
    fun invalidBaseUrlIsABadRequest() {
        val model = OpenAiCompatibleChatModel(http, { "not a url" }, { "m" }, { "k" })
        expectFailure(LlmException.Kind.BadRequest) { model.complete(request) }
    }

    @Test
    fun cancellationAbortsTheCallPromptly() = runBlocking {
        replies += Reply(200, "{}", delayMs = 5_000)
        val started = System.nanoTime()
        val call = async { model().complete(request) }
        delay(200)
        call.cancel()
        try {
            call.await()
            fail("Expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun testConnectionIsTinyAndReadable() = runBlocking {
        replies += ok()
        val summary = model().testConnection()
        assertTrue(summary, Regex("OK · tiny replied in \\d+ ms").matches(summary))
        assertEquals(8, JSONObject(received.single().body).getInt("max_tokens"))
    }
}
