package ai.wakey.android.stt

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Streams real 16 kHz speech through Deepgram Flux at real-time pace (80 ms frames) and prints the
 * measured latencies and transcripts. Skipped unless `DEEPGRAM_API_KEY` is set. Clips are read from
 * `WAKEY_TEST_AUDIO_DIR` when present there, otherwise synthesised once with Deepgram TTS into
 * `build/tmp/flux-live-audio`. Uses about seven short Flux sessions per run.
 */
class DeepgramFluxSttLiveTest {
    private val key: String? = System.getenv("DEEPGRAM_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val stt = DeepgramFluxStt(http) { key }
    private val config = SttConfig(model = "flux-general-multi", languageHints = listOf("en", "hi"), keyterms = listOf("Wakey"))

    @Before
    fun requireKey() = assumeTrue("DEEPGRAM_API_KEY is not set", key != null)

    @After
    fun tearDown() {
        http.dispatcher.executorService.shutdown()
    }

    @Test
    fun `English command ends at Flux's own end of turn`() {
        val clip = englishClip()
        val rec = Recorder()
        val session = stt.open(config, rec)
        val pushTimes = stream(session, clip.pcm + ShortArray(4 * SAMPLE_RATE)) { rec.terminated }
        rec.awaitTerminal()
        assertFinalContains(rec, "flashlight")
        report("english natural EOT (${clip.name})", rec, "speech end", pushTimes[clip.speechEndFrame])
        assertTrue(rec.awaitClosed())
    }

    @Test
    fun `Hinglish command reports its languages`() {
        val clip = clip("stt_flashlight_on_karo_16k.wav", "Flashlight on karo.", MEENA_TTS)
        val rec = Recorder()
        val session = stt.open(config, rec)
        val pushTimes = stream(session, clip.pcm + ShortArray(4 * SAMPLE_RATE)) { rec.terminated }
        rec.awaitTerminal()
        assertFinalContains(rec, "flashlight")
        report("hinglish natural EOT (${clip.name})", rec, "speech end", pushTimes[clip.speechEndFrame])
    }

    @Test
    fun `endTurn before the socket opens still transcribes the held audio`() {
        val clip = englishClip()
        val rec = Recorder()
        val session = stt.open(config, rec)
        session.sendPcm(clip.pcm)
        session.endTurn()
        val endTurnAt = System.nanoTime()
        rec.awaitTerminal()
        assertTrue("endTurn must happen before the connection opens", rec.connectedAt > endTurnAt)
        assertFinalContains(rec, "flashlight")
        report("endTurn before connect", rec, "connected", rec.connectedAt)
    }

    @Test
    fun `push-to-talk release right after speaking finalises the turn`() {
        val clip = englishClip()
        val rec = Recorder()
        val session = stt.open(config, rec)
        // Release right after the last word, before Flux's own end-of-turn could fire.
        stream(session, clip.pcm.copyOf(minOf(clip.pcm.size, (clip.speechEndFrame + 2) * FRAME)))
        val releasedAt = System.nanoTime()
        session.endTurn()
        rec.awaitTerminal()
        assertFinalContains(rec, "flashlight")
        report("push-to-talk endTurn", rec, "release", releasedAt)
    }

    @Test
    fun `endTurn after silence delivers an empty final`() {
        val rec = Recorder()
        val session = stt.open(config, rec)
        stream(session, ShortArray(3 * SAMPLE_RATE / 2))
        val releasedAt = System.nanoTime()
        session.endTurn()
        rec.awaitTerminal()
        assertNull(rec.error)
        assertEquals("", rec.final?.trim())
        report("silence endTurn", rec, "release", releasedAt)
    }

    @Test
    fun `cancel mid-stream stops every callback`() {
        val clip = englishClip()
        val rec = Recorder()
        val session = stt.open(config, rec)
        stream(session, clip.pcm) { rec.partials > 0 }
        session.cancel()
        val callbacksAtCancel = rec.callbacks.get()
        stream(session, clip.pcm.copyOf(SAMPLE_RATE))
        session.endTurn()
        session.cancel()
        Thread.sleep(2_000)
        assertEquals(rec.events.toString(), callbacksAtCancel, rec.callbacks.get())
        assertNull(rec.final)
        assertNull(rec.error)
        println("[flux-live] cancel mid-stream: no callbacks after cancel; before it: ${rec.events}")
    }

    @Test
    fun `an unreachable host is a Network error`() {
        val unreachable = DeepgramFluxStt(http, { "not-a-key" }, "https://wakey-unreachable.invalid/v2/listen".toHttpUrl())
        val rec = Recorder()
        unreachable.open(config, rec).sendPcm(ShortArray(FRAME))
        rec.awaitTerminal()
        assertEquals(SttError.Kind.Network, rec.error?.kind)
        assertTrue(rec.awaitClosed())
        println("[flux-live] unreachable host: ${rec.error}")
    }

    @Test
    fun `a rejected key is an Auth error`() {
        val rejected = DeepgramFluxStt(http) { "0123456789abcdef0123456789abcdef01234567" }
        val rec = Recorder()
        rejected.open(config, rec)
        rec.awaitTerminal()
        assertEquals(SttError.Kind.Auth, rec.error?.kind)
        val failure = runCatching { runBlocking { rejected.testConnection() } }.exceptionOrNull()
        assertTrue("$failure", failure?.message.orEmpty().contains("rejected the API key"))
        println("[flux-live] bad key: ${rec.error}; testConnection: ${failure?.message}")
    }

    @Test
    fun `testConnection reports the connect time`() {
        val summary = runBlocking { stt.testConnection() }
        assertTrue(summary, summary.startsWith("Deepgram OK · connected in "))
        println("[flux-live] testConnection: $summary")
    }

    private fun englishClip(): Clip =
        sharedClip("hey_wakey_flashlight_16k.wav") ?: clip("stt_turn_on_flashlight_16k.wav", "Turn on the flashlight.", AURA_TTS)

    private fun sharedClip(name: String): Clip? =
        System.getenv("WAKEY_TEST_AUDIO_DIR")?.let { File(it, name) }?.takeIf { it.isFile }?.let { Clip(name, readWav(it)) }

    private fun clip(name: String, text: String, ttsUrl: String): Clip {
        sharedClip(name)?.let { return it }
        val cached = File("build/tmp/flux-live-audio", name)
        if (!cached.isFile) {
            val request = Request.Builder().url(ttsUrl)
                .header("Authorization", "Token $key")
                .post(JSONObject().put("text", text).toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Deepgram TTS HTTP ${response.code}" }
                cached.parentFile?.mkdirs()
                cached.writeBytes(response.body!!.bytes())
            }
        }
        return Clip(name, readWav(cached))
    }

    /** Pushes [pcm] in 80 ms frames at real-time pace until done or [stop]; returns each frame's push time. */
    private fun stream(session: SttSession, pcm: ShortArray, stop: () -> Boolean = { false }): List<Long> {
        val pushTimes = mutableListOf<Long>()
        val start = System.nanoTime()
        var offset = 0
        while (offset < pcm.size && !stop()) {
            val end = minOf(offset + FRAME, pcm.size)
            session.sendPcm(pcm.copyOfRange(offset, end))
            pushTimes += System.nanoTime()
            offset = end
            val waitNanos = start + pushTimes.size * FRAME_NANOS - System.nanoTime()
            if (waitNanos > 0) TimeUnit.NANOSECONDS.sleep(waitNanos)
        }
        return pushTimes
    }

    private fun assertFinalContains(rec: Recorder, word: String) {
        rec.error?.let { fail("Expected a final transcript, got $it; events: ${rec.events}") }
        val text = rec.final.orEmpty()
        assertTrue("'$text' should contain '$word'; events: ${rec.events}", text.lowercase().contains(word))
    }

    private fun report(name: String, rec: Recorder, from: String, fromNanos: Long) {
        println(
            "[flux-live] $name: connect=${rec.connectMs} ms, transcriptionMs=${rec.transcriptionMs}, " +
                "$from→final=${(rec.finalAt - fromNanos) / 1_000_000} ms, final='${rec.final}', languages=${rec.languages}",
        )
        rec.snapshot().forEach { println("[flux-live]     $it") }
    }

    private class Clip(val name: String, val pcm: ShortArray) {
        /** Frame holding the last clearly audible sample. */
        val speechEndFrame: Int = pcm.indexOfLast { abs(it.toInt()) > 500 }.coerceAtLeast(0) / FRAME
    }

    private class Recorder : SttListener {
        private val startedAt = System.nanoTime()
        private val terminal = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val callbacks = AtomicInteger()

        @Volatile var connectMs: Long? = null
        @Volatile var connectedAt = 0L
        @Volatile var final: String? = null
        @Volatile var languages: List<String> = emptyList()
        @Volatile var transcriptionMs: Long? = null
        @Volatile var finalAt = 0L
        @Volatile var error: SttError? = null
        @Volatile var partials = 0

        val terminated: Boolean get() = terminal.count == 0L

        fun awaitTerminal() = assertTrue("No final or error in time: $events", terminal.await(20, TimeUnit.SECONDS))

        fun awaitClosed() = closed.await(10, TimeUnit.SECONDS)

        fun snapshot(): List<String> = synchronized(events) { events.toList() }

        override fun onConnected(connectMs: Long) {
            connectedAt = System.nanoTime()
            this.connectMs = connectMs
            record("connected in $connectMs ms")
        }

        override fun onSpeechStarted() = record("speech started")

        override fun onTranscript(text: String, isFinal: Boolean, languages: List<String>, transcriptionMs: Long?) {
            if (!isFinal) {
                partials++
                return record("partial '$text' $languages")
            }
            finalAt = System.nanoTime()
            final = text
            this.languages = languages
            this.transcriptionMs = transcriptionMs
            record("FINAL '$text' $languages transcriptionMs=$transcriptionMs")
            terminal.countDown()
        }

        override fun onError(error: SttError) {
            this.error = error
            record("error $error")
            terminal.countDown()
        }

        override fun onClosed() {
            record("closed")
            closed.countDown()
        }

        private fun record(event: String) {
            callbacks.incrementAndGet()
            events += "+%d ms %s".format((System.nanoTime() - startedAt) / 1_000_000, event)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME = SAMPLE_RATE * FluxSession.CHUNK_MS / 1000
        const val FRAME_NANOS = FluxSession.CHUNK_MS * 1_000_000L
        const val AURA_TTS = "https://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=linear16&sample_rate=16000&container=wav"
        const val MEENA_TTS = "https://api.deepgram.com/v2/speak?model=flux-meena-en&encoding=linear16&sample_rate=16000&container=wav"

        /** Reads 16 kHz mono PCM16 WAV, including streamed WAVs whose data size is unset (0xFFFFFFFF). */
        fun readWav(file: File): ShortArray {
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "$file is not a WAV" }
            var pos = 12
            var format = ""
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = header.getInt(pos + 4).toLong() and 0xFFFF_FFFFL
                val body = pos + 8
                if (id == "fmt ") {
                    format = "${header.getShort(body + 2)} ch, ${header.getInt(body + 4)} Hz, ${header.getShort(body + 14)} bit"
                }
                if (id == "data") {
                    check(format == "1 ch, 16000 Hz, 16 bit") { "$file must be 16 kHz mono PCM16, is $format" }
                    val end = minOf(bytes.size.toLong(), body + size).toInt()
                    return ShortArray((end - body) / 2).also {
                        ByteBuffer.wrap(bytes, body, end - body).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
                    }
                }
                pos = body + size.toInt() + (size.toInt() and 1)
            }
            error("$file has no data chunk")
        }
    }
}
