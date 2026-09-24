package ai.wakey.android.stt

import ai.wakey.android.agent.AppMatcher
import ai.wakey.android.agent.FastCommand
import ai.wakey.android.agent.FastCommandRouter
import ai.wakey.android.agent.LauncherApp
import ai.wakey.android.core.AssistantController
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Streams the spoken-command test set through Deepgram Flux at real-time pace (80 ms frames) and
 * prints, per clip, the final transcript, detected languages, speech end → final latency and what
 * the fast-command router makes of it. Skipped unless `DEEPGRAM_API_KEY` is set and
 * `WAKEY_TEST_AUDIO_DIR` holds `cmd_manifest.tsv` (clip, voice, speed, expected route, text).
 *
 * - [sessions]: one [DeepgramFluxStt] session per clip, as the app runs it (one Flux session each).
 * - [oneStream]: every clip as a consecutive turn of a single raw Flux stream (one session in
 *   total), to compare query parameters cheaply. Also prints Flux's end-of-turn trigger and
 *   confidence, which the app does not parse.
 *
 * Optional overrides: `WAKEY_FLUX_HINTS` ("en,hi", "en" or "none"), `WAKEY_FLUX_EOT`,
 * `WAKEY_FLUX_TIMEOUT`, `WAKEY_FLUX_KEYTERMS` ("app", "none" or "a|b|c"), `WAKEY_FLUX_CLIPS`
 * (comma-separated name fragments), `WAKEY_FLUX_OUT` (file the report is appended to).
 */
class FluxCommandSetLiveTest {
    private val key: String? = System.getenv("DEEPGRAM_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    private val audioDir: File? = System.getenv("WAKEY_TEST_AUDIO_DIR")?.let(::File)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val turns = FluxTurnTuning(
        eotThreshold = env("WAKEY_FLUX_EOT") ?: DeepgramFluxStt.EOT_THRESHOLD,
        eotTimeoutMs = env("WAKEY_FLUX_TIMEOUT")?.toInt() ?: DeepgramFluxStt.EOT_TIMEOUT_MS,
    )
    private val config = SttConfig(
        model = "flux-general-multi",
        languageHints = when (val hints = env("WAKEY_FLUX_HINTS") ?: "en,hi") {
            "none" -> emptyList()
            else -> hints.split(',').map { it.trim() }
        },
        keyterms = when (val terms = env("WAKEY_FLUX_KEYTERMS") ?: "app") {
            "app" -> APP_KEYTERMS
            "none" -> emptyList()
            else -> terms.split('|')
        },
    )
    private val report = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun requireKeyAndClips() {
        assumeTrue("DEEPGRAM_API_KEY is not set", key != null)
        assumeTrue("WAKEY_TEST_AUDIO_DIR has no cmd_manifest.tsv", audioDir?.resolve(MANIFEST)?.isFile == true)
    }

    @After
    fun tearDown() {
        http.dispatcher.executorService.shutdown()
        env("WAKEY_FLUX_OUT")?.let { File(it).appendText(report.joinToString("\n", postfix = "\n")) }
    }

    @Test
    fun sessions() {
        val stt = DeepgramFluxStt(http, { key }, DeepgramFluxStt.LISTEN_ENDPOINT, turns)
        log("== sessions ${describeConfig()}")
        val results = clips().map { clip ->
            val rec = Recorder()
            val session = stt.open(config, rec)
            val pushTimes = stream(clip.pcm + noise(MAX_WAIT_MS), { rec.terminated }) { session.sendPcm(it) }
            check(rec.terminal.await(20, TimeUnit.SECONDS)) { "no final for ${clip.name}: ${rec.events}" }
            session.cancel()
            val result = Result(
                clip, rec.final.orEmpty(), rec.languages, rec.error?.toString(),
                endToFinalMs = (rec.finalAt - pushTimes[clip.speechEndFrame.coerceAtMost(pushTimes.lastIndex)]) / NANOS_PER_MS,
                connectMs = rec.connectMs, transcriptionMs = rec.transcriptionMs,
                firstPartialMs = rec.firstPartialAt.takeIf { it > 0 }?.let { (it - pushTimes[clip.speechStartFrame]) / NANOS_PER_MS },
                partials = rec.partials, extra = "",
            )
            log(result.line())
            Thread.sleep(300)
            result
        }
        summarise(results)
    }

    @Test
    fun oneStream() {
        val clips = clips()
        val flux = RawFluxStream(fluxListenUrl(DeepgramFluxStt.LISTEN_ENDPOINT, config, turns))
        log("== oneStream ${describeConfig()}")
        check(flux.opened.await(10, TimeUnit.SECONDS)) { "Flux did not connect" }
        val results = clips.map { clip ->
            val firstEvent = flux.turns.size
            // The whole clip, even if a turn ends inside it; then room tone until a turn ends after the speech.
            val pushTimes = stream(clip.pcm, { false }) { flux.send(it) }
            val speechEndAt = pushTimes[clip.speechEndFrame]
            fun endedAfterSpeech() = flux.turnsSince(firstEvent).any { it.isFinal && it.at > speechEndAt }
            if (!endedAfterSpeech()) stream(noise(MAX_WAIT_MS), ::endedAfterSpeech) { flux.send(it) }
            val clipTurns = flux.turnsSince(firstEvent)
            val ended = clipTurns.filter { it.isFinal }
            val partials = clipTurns.filter { !it.isFinal && it.event != "EndOfTurn" && it.transcript.isNotBlank() }
            val last = ended.lastOrNull()
            val result = Result(
                clip, ended.joinToString(" | ") { it.transcript }, last?.languages.orEmpty(),
                error = if (ended.isEmpty()) "no EndOfTurn" else null,
                endToFinalMs = last?.let { (it.at - speechEndAt) / NANOS_PER_MS },
                connectMs = null, transcriptionMs = null,
                firstPartialMs = partials.firstOrNull()?.let { (it.at - pushTimes[clip.speechStartFrame]) / NANOS_PER_MS },
                partials = partials.size,
                extra = "trigger=${last?.trigger} eotConf=${last?.eotConfidence} words=${last?.wordTimes}" +
                    if (ended.size > 1) " SPLIT(${ended.size})" else "",
            )
            log(result.line())
            result
        }
        flux.close()
        summarise(results)
    }

    private fun describeConfig() =
        "hints=${config.languageHints} eot=${turns.eotThreshold} timeout=${turns.eotTimeoutMs} keyterms=${config.keyterms.size}"

    private fun summarise(results: List<Result>) {
        val ok = results.count { it.correct }
        val latencies = results.mapNotNull { it.endToFinalMs }.sorted()
        val median = latencies.getOrNull(latencies.size / 2)
        log(
            "== correct $ok/${results.size}; end→final median=$median ms, max=${latencies.lastOrNull()} ms, " +
                "mean=${latencies.average().roundToInt()} ms; devanagari=${results.count { it.final.any { c -> c in 'ऀ'..'ॿ' } }}",
        )
    }

    private fun clips(): List<Clip> {
        val filter = env("WAKEY_FLUX_CLIPS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return audioDir!!.resolve(MANIFEST).readLines().filter { it.isNotBlank() }.map { line ->
            val (name, voice, speed, expected) = line.split('\t')
            Clip(name, "$voice@$speed", expected, readPcm(audioDir.resolve(name)))
        }.filter { clip -> filter.isEmpty() || filter.any { it in clip.name } }
    }

    /** Pushes [pcm] in 80 ms frames at real-time pace until done or [stop]; returns each frame's push time. */
    private fun stream(pcm: ShortArray, stop: () -> Boolean, send: (ShortArray) -> Unit): List<Long> {
        val pushTimes = mutableListOf<Long>()
        val start = System.nanoTime()
        var offset = 0
        while (offset < pcm.size && !stop()) {
            val end = minOf(offset + FRAME, pcm.size)
            send(pcm.copyOfRange(offset, end))
            pushTimes += System.nanoTime()
            offset = end
            val waitNanos = start + pushTimes.size * FRAME_NANOS - System.nanoTime()
            if (waitNanos > 0) TimeUnit.NANOSECONDS.sleep(waitNanos)
        }
        return pushTimes
    }

    private fun log(line: String) {
        println("[flux-cmd] $line")
        report += line
    }

    private class Clip(val name: String, val voice: String, val expected: String, val pcm: ShortArray) {
        val speechStartFrame: Int = pcm.indexOfFirst { abs(it.toInt()) > 500 }.coerceAtLeast(0) / FRAME
        val speechEndFrame: Int = pcm.indexOfLast { abs(it.toInt()) > 500 }.coerceAtLeast(0) / FRAME
    }

    private class Result(
        val clip: Clip,
        val final: String,
        val languages: List<String>,
        val error: String?,
        val endToFinalMs: Long?,
        val connectMs: Long?,
        val transcriptionMs: Long?,
        val firstPartialMs: Long?,
        val partials: Int,
        val extra: String,
    ) {
        val route: String = routeOf(final)
        val correct: Boolean = route == clip.expected

        fun line() = "${clip.name.removePrefix("cmd_").removeSuffix("_16k.wav")} [${clip.voice}] " +
            "final='$final' lang=$languages end→final=${endToFinalMs}ms transcriptionMs=$transcriptionMs " +
            "connect=${connectMs}ms firstPartial=+${firstPartialMs}ms partials=$partials route=$route " +
            "expected=${clip.expected} ${if (correct) "OK" else "WRONG"}" +
            (error?.let { " error=$it" } ?: "") + (if (extra.isNotEmpty()) " $extra" else "")
    }

    private class Recorder : SttListener {
        val terminal = CountDownLatch(1)
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile var connectMs: Long? = null
        @Volatile var final: String? = null
        @Volatile var languages: List<String> = emptyList()
        @Volatile var transcriptionMs: Long? = null
        @Volatile var finalAt = 0L
        @Volatile var firstPartialAt = 0L
        @Volatile var partials = 0
        @Volatile var error: SttError? = null

        val terminated: Boolean get() = terminal.count == 0L

        override fun onConnected(connectMs: Long) {
            this.connectMs = connectMs
        }

        override fun onTranscript(text: String, isFinal: Boolean, languages: List<String>, transcriptionMs: Long?) {
            if (!isFinal) {
                if (firstPartialAt == 0L) firstPartialAt = System.nanoTime()
                partials++
                events += "partial '$text'"
                return
            }
            finalAt = System.nanoTime()
            final = text
            this.languages = languages
            this.transcriptionMs = transcriptionMs
            terminal.countDown()
        }

        override fun onError(error: SttError) {
            this.error = error
            finalAt = System.nanoTime()
            terminal.countDown()
        }
    }

    private class Turn(
        val event: String,
        val transcript: String,
        val languages: List<String>,
        val trigger: String?,
        val eotConfidence: String?,
        val wordTimes: String,
        val at: Long,
    ) {
        val isFinal: Boolean get() = event == "EndOfTurn" && transcript.isNotBlank()
    }

    /** A bare Flux stream that keeps going across turns, unlike [FluxSession]. */
    private inner class RawFluxStream(url: okhttp3.HttpUrl) {
        val opened = CountDownLatch(1)
        val turns: MutableList<Turn> = Collections.synchronizedList(mutableListOf())
        private val socket: WebSocket = http.newWebSocket(
            Request.Builder().url(url).header("Authorization", "Token $key").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JSONObject(text)
                    if (json.optString("type") != "TurnInfo") return
                    val words = json.optJSONArray("words")
                    val times = (0 until (words?.length() ?: 0)).joinToString(",") { i ->
                        val w = words!!.getJSONObject(i)
                        "%.2f-%.2f".format(w.optDouble("start"), w.optDouble("end"))
                    }
                    val languages = json.optJSONArray("languages")
                    turns += Turn(
                        json.optString("event"), json.optString("transcript"),
                        (0 until (languages?.length() ?: 0)).map { languages!!.getString(it) },
                        json.optString("trigger").takeIf { it.isNotEmpty() },
                        json.opt("end_of_turn_confidence")?.toString(), times, System.nanoTime(),
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    log("stream failure: $t ${response?.code}")
                }
            },
        )

        fun send(pcm: ShortArray) {
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { bytes.putShort(it) }
            socket.send(bytes.array().toByteString())
        }

        fun turnsSince(index: Int): List<Turn> = synchronized(turns) { turns.drop(index) }

        fun close() {
            socket.send(CLOSE_STREAM)
            Thread.sleep(500)
            socket.close(1000, null)
        }
    }

    private companion object {
        const val MANIFEST = "cmd_manifest.tsv"
        const val SAMPLE_RATE = 16_000
        const val FRAME = SAMPLE_RATE * FluxSession.CHUNK_MS / 1000
        const val FRAME_NANOS = FluxSession.CHUNK_MS * 1_000_000L
        const val NANOS_PER_MS = 1_000_000L

        /** Room tone streamed after each clip while waiting for the final, as an open mic would. */
        const val MAX_WAIT_MS = 6_000
        const val NOISE_RMS = 32.0

        /** What AssistantController.keyterms sends with the default "Hey Wakey" phrase. */
        val APP_KEYTERMS = listOf(
            "Wakey", "flashlight", "torch", "Calculator", "YouTube", "WhatsApp", "Chrome", "Settings", "Bluetooth",
            "kholo", "karo", "jalao", "band karo",
        )

        /** Launcher entries of a typical phone, to check what an OpenApp name would really open. */
        val PHONE_APPS = listOf(
            LauncherApp("Calculator", "com.google.android.calculator", ".Calculator"),
            LauncherApp("YouTube", "com.google.android.youtube", ".Home"),
            LauncherApp("WhatsApp", "com.whatsapp", ".Main"),
            LauncherApp("Instagram", "com.instagram.android", ".Main"),
            LauncherApp("Chrome", "com.android.chrome", ".Main"),
            LauncherApp("Settings", "com.android.settings", ".Settings"),
            LauncherApp("Camera", "com.google.android.GoogleCamera", ".Camera"),
        )

        private val random = Random(11)

        fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

        fun noise(ms: Int): ShortArray = ShortArray(SAMPLE_RATE * ms / 1000) { (random.nextGaussian() * NOISE_RMS).roundToInt().toShort() }

        /** The route the controller takes for a final transcript, as "torch:on", "open:<label>" or "agent". */
        fun routeOf(final: String): String {
            val cleaned = AssistantController.stripWakePhrase(final, "Hey Wakey")
            if (cleaned.isBlank()) return if (final.isBlank()) "empty" else "wake-only"
            return when (val command = FastCommandRouter.route(cleaned)) {
                null -> "agent"
                is FastCommand.Torch -> if (command.on) "torch:on" else "torch:off"
                is FastCommand.OpenApp -> "open:" + (AppMatcher.match(command.appName, PHONE_APPS)?.label?.lowercase() ?: "?${command.appName}")
                FastCommand.GoHome -> "home"
                FastCommand.GoBack -> "back"
            }
        }

        fun readPcm(file: File): ShortArray {
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = header.getInt(pos + 4)
                if (id == "data") {
                    return ShortArray(size / 2).also {
                        ByteBuffer.wrap(bytes, pos + 8, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
                    }
                }
                pos += 8 + size + (size and 1)
            }
            error("$file has no data chunk")
        }
    }
}
