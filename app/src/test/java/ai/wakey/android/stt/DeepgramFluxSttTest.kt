package ai.wakey.android.stt

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DeepgramFluxSttTest {
    private val http = OkHttpClient()

    @After
    fun tearDown() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    @Test
    fun `listen URL repeats language hints and URL-encodes keyterms`() {
        val config = SttConfig(
            model = "flux-general-multi",
            languageHints = listOf("en", " hi ", "", "en"),
            keyterms = listOf("Wakey", "Hey Wakey", "R&D+", " ", "Wakey"),
        )
        val url = fluxListenUrl(DeepgramFluxStt.LISTEN_ENDPOINT, config)
        assertEquals(
            "https://api.deepgram.com/v2/listen?model=flux-general-multi&encoding=linear16&sample_rate=16000" +
                "&eot_threshold=0.7&eot_timeout_ms=3000&language_hint=en&language_hint=hi" +
                "&keyterm=Wakey&keyterm=Hey%20Wakey&keyterm=R%26D%2B",
            url.toString(),
        )
        assertEquals(listOf("Wakey", "Hey Wakey", "R&D+"), url.queryParameterValues("keyterm"))
    }

    @Test
    fun `listen URL carries the end-of-turn tuning`() {
        val config = SttConfig(model = "flux-general-multi", languageHints = emptyList())
        val url = fluxListenUrl(DeepgramFluxStt.LISTEN_ENDPOINT, config, FluxTurnTuning(eotThreshold = "0.65", eotTimeoutMs = 1_500))
        assertEquals("0.65", url.queryParameter("eot_threshold"))
        assertEquals("1500", url.queryParameter("eot_timeout_ms"))
        assertEquals(FluxTurnTuning("0.7", 3_000), FluxTurnTuning())
    }

    @Test
    fun `listen URL omits language hints for models that reject them`() {
        val url = fluxListenUrl(
            DeepgramFluxStt.LISTEN_ENDPOINT,
            SttConfig(model = "flux-general-en", languageHints = listOf("en", "hi"), sampleRate = 8_000),
        )
        assertEquals(emptyList<String>(), url.queryParameterValues("language_hint"))
        assertEquals("8000", url.queryParameter("sample_rate"))
    }

    @Test
    fun `auto-detect sends no language hint`() {
        val url = fluxListenUrl(DeepgramFluxStt.LISTEN_ENDPOINT, SttConfig(model = "flux-general-multi", languageHints = emptyList()))
        assertEquals(null, url.queryParameter("language_hint"))
    }

    @Test
    fun `maps upgrade and transport failures`() {
        val upgrade = ProtocolException("Expected HTTP 101 response but was '401 Unauthorized'")
        assertEquals(SttError.Kind.Auth, fluxFailure(upgrade, 401, null).kind)
        assertEquals(SttError.Kind.Auth, fluxFailure(upgrade, 403, null).kind)
        assertEquals(
            SttError(SttError.Kind.Server, "Deepgram HTTP 400: Invalid query string."),
            fluxFailure(upgrade, 400, "Invalid query string."),
        )
        assertEquals(SttError(SttError.Kind.Server, "Deepgram HTTP 429"), fluxFailure(upgrade, 429, " "))
        assertEquals(
            SttError(SttError.Kind.Network, "Could not reach Deepgram: timeout"),
            fluxFailure(SocketTimeoutException("timeout"), null, null),
        )
        assertEquals(SttError.Kind.Network, fluxFailure(UnknownHostException("api.deepgram.com"), null, null).kind)
        assertEquals(SttError.Kind.Protocol, fluxFailure(ProtocolException("Control frames must be final."), null, null).kind)
        assertEquals(SttError.Kind.Protocol, fluxFailure(IllegalStateException("odd"), null, null).kind)
    }

    @Test
    fun `open without a key reports MissingKey asynchronously and never throws`() {
        val listener = LatchListener()
        val openedOn = Thread.currentThread()
        val session = DeepgramFluxStt(http) { "  " }.open(CONFIG, listener)
        session.sendPcm(ShortArray(1280))
        session.endTurn()
        assertTrue(listener.awaitClosed())
        assertEquals(listOf("error MissingKey", "closed"), listener.log)
        assertTrue(listener.threads.none { it == openedOn })
    }

    @Test
    fun `a key lookup that throws counts as missing`() {
        val listener = LatchListener()
        DeepgramFluxStt(http) { error("keystore locked") }.open(CONFIG, listener)
        assertTrue(listener.awaitClosed())
        assertEquals(listOf("error MissingKey", "closed"), listener.log)
    }

    @Test
    fun `a refused connection is a Network error`() {
        val port = ServerSocket(0).use { it.localPort }
        val stt = DeepgramFluxStt(http, { "test-key" }, "http://127.0.0.1:$port/v2/listen".toHttpUrl())
        val listener = LatchListener()
        stt.open(CONFIG, listener)
        assertTrue(listener.awaitClosed())
        assertEquals(listOf("error Network", "closed"), listener.log)
    }

    @Test
    fun `HTTP 401 on upgrade is an Auth error, and the request carries the key and parameters`() {
        val server = FakeHttpServer("401 Unauthorized")
        val stt = DeepgramFluxStt(http, { "test-key" }, "http://127.0.0.1:${server.port}/v2/listen".toHttpUrl())
        val listener = LatchListener()
        stt.open(CONFIG.copy(keyterms = listOf("Hey Wakey")), listener)
        assertTrue(listener.awaitClosed())
        assertEquals(listOf("error Auth", "closed"), listener.log)
        val head = server.requestHead()
        assertTrue(head, head.startsWith("GET /v2/listen?model=flux-general-multi&encoding=linear16&sample_rate=16000&"))
        assertTrue(head, "&language_hint=en&language_hint=hi&keyterm=Hey%20Wakey HTTP/1.1" in head)
        assertTrue(head, "\r\nAuthorization: Token test-key\r\n" in head)
        assertTrue(head, "\r\nUpgrade: websocket\r\n" in head)
    }

    @Test
    fun `other HTTP statuses on upgrade are Server errors with Deepgram's reason`() {
        val server = FakeHttpServer("400 Bad Request", "dg-error: Invalid language_hint.")
        val stt = DeepgramFluxStt(http, { "test-key" }, "http://127.0.0.1:${server.port}/v2/listen".toHttpUrl())
        val listener = LatchListener()
        stt.open(CONFIG, listener)
        assertTrue(listener.awaitClosed())
        assertEquals(listOf("error Server", "closed"), listener.log)
        assertEquals("Deepgram HTTP 400: Invalid language_hint.", listener.errors.single().message)
    }

    /** Answers one HTTP request with [status] and remembers the request head. */
    private class FakeHttpServer(status: String, vararg headers: String) {
        private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val head = StringBuilder()
        private val done = CountDownLatch(1)
        val port = socket.localPort

        init {
            thread(isDaemon = true) {
                socket.use { server ->
                    server.accept().use { client ->
                        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            head.append(line).append("\r\n")
                        }
                        val extra = headers.joinToString("") { "$it\r\n" }
                        client.getOutputStream().write("HTTP/1.1 $status\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        client.getOutputStream().flush()
                    }
                }
                done.countDown()
            }
        }

        fun requestHead(): String {
            assertTrue(done.await(5, TimeUnit.SECONDS))
            return head.toString()
        }
    }

    private class LatchListener : SttListener {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val errors: MutableList<SttError> = Collections.synchronizedList(mutableListOf())
        val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
        private val closed = CountDownLatch(1)

        fun awaitClosed() = closed.await(10, TimeUnit.SECONDS)

        override fun onTranscript(text: String, isFinal: Boolean, languages: List<String>, transcriptionMs: Long?) {
            threads += Thread.currentThread()
            log += "transcript"
        }

        override fun onError(error: SttError) {
            threads += Thread.currentThread()
            errors += error
            log += "error ${error.kind}"
        }

        override fun onClosed() {
            threads += Thread.currentThread()
            log += "closed"
            closed.countDown()
        }
    }

    private companion object {
        val CONFIG = SttConfig(model = "flux-general-multi", languageHints = listOf("en", "hi"))
    }
}
