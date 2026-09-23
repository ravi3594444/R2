package ai.wakey.android.stt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler.advanceTimeBy
class FluxSessionTest {
    private val scheduler = TestCoroutineScheduler()
    private val socket = FakeSocket()
    private val calls = RecordingListener()
    private lateinit var server: FluxSocket.Listener
    private var nowMs = 0L

    private fun open(): FluxSession =
        FluxSession(CONFIG, calls, StandardTestDispatcher(scheduler)) { nowMs * 1_000_000 }.also { session ->
            session.start { listener ->
                server = listener
                socket
            }
        }

    private fun connected(): FluxSession = open().also {
        nowMs += 300
        server.onOpen()
        settle()
        calls.log.clear()
    }

    private fun settle() = scheduler.runCurrent()

    private fun FluxSession.push(frames: Int, marker: Int = 0) {
        for (i in 0 until frames) sendPcm(ShortArray(FRAME) { (marker + i).toShort() })
    }

    @Test
    fun `holds audio while connecting, then flushes it in order before live audio`() {
        val session = open()
        session.push(2, marker = 1)
        session.sendPcm(ShortArray(FRAME / 2) { 3 })
        settle()
        assertEquals(emptyList<String>(), socket.frames)

        nowMs = 420
        server.onOpen()
        settle()
        assertEquals(listOf("connected 420"), calls.log)
        assertEquals(listOf("pcm 2560 #1", "pcm 2560 #2"), socket.frames)

        session.sendPcm(ShortArray(FRAME / 2) { 4 })
        settle()
        assertEquals("pcm 2560 #3", socket.frames.last())
    }

    @Test
    fun `drops the oldest held audio beyond 15 s`() {
        val session = open()
        session.push(200)
        server.onOpen()
        settle()
        val kept = FluxSession.MAX_PENDING_MS / FluxSession.CHUNK_MS
        assertEquals(kept, socket.frames.size)
        assertEquals("pcm 2560 #${200 - kept}", socket.frames.first())
        assertEquals("pcm 2560 #199", socket.frames.last())
    }

    @Test
    fun `partials, then exactly one final, then CloseStream and close`() {
        connected()
        server.onText("""{"type":"Connected","request_id":"r","sequence_id":0}""")
        server.onText(turn("Update", ""))
        server.onText(turn("StartOfTurn", "Turn"))
        server.onText(turn("Update", "Turn on"))
        server.onText(turn("Update", "Turn on"))
        server.onText(turn("EndOfTurn", "Turn on the flashlight."))
        server.onText(turn("EndOfTurn", "Something else"))
        server.onText(turn("Update", "late"))
        settle()
        assertEquals(
            listOf("speech", "partial 'Turn'", "partial 'Turn on'", "final 'Turn on the flashlight.' [en]"),
            calls.log,
        )
        assertEquals(listOf(CLOSE_STREAM), socket.texts)
        assertTrue(socket.closed)

        server.onClosed(1005, "")
        server.onClosed(1000, "")
        settle()
        assertEquals("closed", calls.log.last())
        assertEquals(1, calls.log.count { it == "closed" })
    }

    @Test
    fun `a blank EndOfTurn keeps the session listening`() {
        val session = connected()
        server.onText(turn("StartOfTurn", "Hmm"))
        server.onText(turn("EndOfTurn", " "))
        session.push(1, marker = 7)
        server.onText(turn("StartOfTurn", "Open"))
        server.onText(turn("EndOfTurn", "Open YouTube"))
        settle()
        assertEquals(
            listOf("speech", "partial 'Hmm'", "speech", "partial 'Open'", "final 'Open YouTube' [en]"),
            calls.log,
        )
        assertTrue("pcm 2560 #7" in socket.frames)
    }

    @Test
    fun `endTurn before connecting sends the held audio, then ForceEndTurn and CloseStream`() {
        val session = open()
        session.push(1, marker = 1)
        session.sendPcm(ShortArray(20) { 2 })
        session.endTurn()
        session.push(1, marker = 9)
        settle()
        assertEquals(emptyList<String>(), socket.frames)

        server.onOpen()
        settle()
        assertEquals(listOf("pcm 2560 #1", "pcm 40 #2", FORCE_END_TURN, CLOSE_STREAM), socket.frames)

        // Flux had not decoded a turn yet when ForceEndTurn arrived; CloseStream decodes the rest.
        server.onText("""{"type":"Warning","code":"FORCE_END_TURN_NO_ACTIVE_TURN","description":"ignored"}""")
        server.onText(turn("StartOfTurn", "Turn on"))
        server.onText(turn("Update", "Turn on the flashlight."))
        server.onClosed(1005, "")
        settle()
        assertEquals(
            listOf("connected 0", "speech", "partial 'Turn on'", "partial 'Turn on the flashlight.'", "final 'Turn on the flashlight.' [en]", "closed"),
            calls.log,
        )
    }

    @Test
    fun `endTurn with nothing said delivers an empty final`() {
        val session = connected()
        session.push(3)
        server.onText(turn("Update", "", languages = emptyList()))
        session.endTurn()
        settle()
        assertEquals(listOf(FORCE_END_TURN, CLOSE_STREAM), socket.texts)

        server.onText("""{"type":"Warning","code":"FORCE_END_TURN_NO_ACTIVE_TURN","description":"ignored"}""")
        server.onClosed(1005, "")
        settle()
        assertEquals(listOf("final '' []", "closed"), calls.log)
    }

    @Test
    fun `endTurn during a turn uses the manual EndOfTurn`() {
        val session = connected()
        server.onText(turn("StartOfTurn", "Calculator"))
        session.endTurn()
        session.endTurn()
        server.onText(turn("EndOfTurn", "Calculator kholo.", languages = listOf("hi", "en")))
        settle()
        assertEquals(listOf("speech", "partial 'Calculator'", "final 'Calculator kholo.' [hi, en]"), calls.log)
        assertEquals(listOf(FORCE_END_TURN, CLOSE_STREAM), socket.texts)
        assertTrue(socket.closed)
    }

    @Test
    fun `audio after endTurn is not sent`() {
        val session = connected()
        session.push(1, marker = 1)
        session.endTurn()
        session.push(1, marker = 2)
        settle()
        assertEquals(listOf("pcm 2560 #1", FORCE_END_TURN, CLOSE_STREAM), socket.frames)
    }

    @Test
    fun `close sends only CloseStream and reports the latest update`() {
        val session = connected()
        server.onText(turn("StartOfTurn", "Open"))
        server.onText(turn("Update", "Open Settings"))
        session.close()
        settle()
        assertEquals(listOf(CLOSE_STREAM), socket.texts)
        server.onClosed(1006, "")
        settle()
        assertEquals(listOf("speech", "partial 'Open'", "partial 'Open Settings'", "final 'Open Settings' [en]", "closed"), calls.log)
    }

    @Test
    fun `an unexpected close before the final is a Network error`() {
        connected()
        server.onText(turn("StartOfTurn", "Turn"))
        server.onClosed(1011, "internal")
        server.onText(turn("EndOfTurn", "Turn on"))
        settle()
        assertEquals(listOf("speech", "partial 'Turn'", "error Network: Deepgram closed the connection (code 1011: internal)", "closed"), calls.log)
        assertTrue(socket.cancelled)
    }

    @Test
    fun `Flux's fatal Error is a Server error`() {
        connected()
        server.onText("""{"type":"Error","sequence_id":2,"code":"INTERNAL_SERVER_ERROR","description":"Decoder failed"}""")
        server.onClosed(1011, "")
        settle()
        assertEquals(listOf("error Server: Decoder failed (INTERNAL_SERVER_ERROR)", "closed"), calls.log)
    }

    @Test
    fun `an unreadable server message is a Protocol error`() {
        connected()
        server.onText("<html>")
        settle()
        assertEquals(listOf("error Protocol: Unreadable message from Deepgram", "closed"), calls.log)
    }

    @Test
    fun `a transport failure is reported once`() {
        open()
        server.onFailure(SttError(SttError.Kind.Auth, "Deepgram rejected the API key (HTTP 401)"))
        server.onClosed(1000, "")
        settle()
        assertEquals(listOf("error Auth: Deepgram rejected the API key (HTTP 401)", "closed"), calls.log)
    }

    @Test
    fun `a missing key is reported asynchronously`() {
        val session = FluxSession(CONFIG, calls, StandardTestDispatcher(scheduler)) { 0 }
        session.startFailed(SttError(SttError.Kind.MissingKey, "No Deepgram API key saved"))
        session.sendPcm(ShortArray(FRAME))
        session.endTurn()
        assertEquals(emptyList<String>(), calls.log)
        settle()
        assertEquals(listOf("error MissingKey: No Deepgram API key saved", "closed"), calls.log)
    }

    @Test
    fun `cancel stops every callback, drops the socket and is idempotent`() {
        val session = connected()
        server.onText(turn("StartOfTurn", "Turn"))
        settle()
        session.cancel()
        session.cancel()
        server.onText(turn("EndOfTurn", "Turn on the flashlight."))
        server.onClosed(1000, "")
        session.push(2)
        session.endTurn()
        scheduler.advanceUntilIdle()
        assertEquals(listOf("speech", "partial 'Turn'"), calls.log)
        assertTrue(socket.cancelled)
        assertFalse(socket.frames.any { it.startsWith("pcm") })
    }

    @Test
    fun `gives up when Flux never answers endTurn`() {
        val session = connected()
        server.onText(turn("StartOfTurn", "Turn"))
        session.endTurn()
        settle()
        scheduler.advanceTimeBy(FluxSession.CLOSE_TIMEOUT_MS)
        settle()
        assertEquals(
            listOf("speech", "partial 'Turn'", "error Network: Timed out waiting for Deepgram's final transcript", "closed"),
            calls.log,
        )
        assertTrue(socket.cancelled)
    }

    @Test
    fun `gives up when the connection never opens`() {
        val session = open()
        session.push(1)
        settle()
        scheduler.advanceTimeBy(FluxSession.CONNECT_TIMEOUT_MS)
        settle()
        assertEquals(listOf("error Network: Timed out connecting to Deepgram", "closed"), calls.log)
        assertTrue(socket.cancelled)
    }

    @Test
    fun `the connect deadline does not apply once open`() {
        connected()
        scheduler.advanceTimeBy(FluxSession.CONNECT_TIMEOUT_MS * 2)
        settle()
        assertEquals(emptyList<String>(), calls.log)
        assertFalse(socket.cancelled)
    }

    @Test
    fun `drops the socket if Flux does not hang up after the final`() {
        connected()
        server.onText(turn("EndOfTurn", "Go home"))
        settle()
        assertFalse(socket.cancelled)
        scheduler.advanceTimeBy(FluxSession.CLOSE_TIMEOUT_MS)
        settle()
        assertEquals(listOf("final 'Go home' [en]", "closed"), calls.log)
        assertTrue(socket.cancelled)
    }

    @Test
    fun `transcription time runs from the push of the last word's frame to the final`() {
        val session = connected()
        nowMs = 1_000
        session.push(1) // 0.00–0.08 s of stream audio
        settle()
        nowMs = 1_080
        session.push(1) // 0.08–0.16 s
        settle()
        nowMs = 1_160
        session.push(1)
        settle()
        nowMs = 1_500
        server.onText(turn("EndOfTurn", "Go back", lastWordEnd = 0.1))
        settle()
        assertEquals(420L, calls.transcriptionMs)
    }

    private class FakeSocket : FluxSocket {
        val frames = mutableListOf<String>()
        val texts get() = frames.filterNot { it.startsWith("pcm") }
        var closed = false
        var cancelled = false

        override fun sendBinary(bytes: ByteArray) {
            // Frames carry a marker in their first sample (little-endian low byte).
            frames += "pcm ${bytes.size} #${bytes[0].toInt() and 0xFF}"
        }

        override fun sendText(text: String) {
            frames += text
        }

        override fun close() {
            closed = true
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private class RecordingListener : SttListener {
        val log = mutableListOf<String>()
        var transcriptionMs: Long? = null

        override fun onConnected(connectMs: Long) {
            log += "connected $connectMs"
        }

        override fun onSpeechStarted() {
            log += "speech"
        }

        override fun onTranscript(text: String, isFinal: Boolean, languages: List<String>, transcriptionMs: Long?) {
            log += if (isFinal) "final '$text' $languages" else "partial '$text'"
            if (isFinal) this.transcriptionMs = transcriptionMs
        }

        override fun onError(error: SttError) {
            log += "error ${error.kind}: ${error.message}"
        }

        override fun onClosed() {
            log += "closed"
        }
    }

    private companion object {
        val CONFIG = SttConfig(model = "flux-general-multi", languageHints = listOf("en", "hi"))
        const val FRAME = 1280

        fun turn(event: String, transcript: String, lastWordEnd: Double? = null, languages: List<String> = listOf("en")): String {
            val words = lastWordEnd?.let { """[{"word":"w","confidence":0.9,"start":0.0,"end":$it}]""" } ?: "[]"
            val langs = languages.joinToString(",") { "\"$it\"" }
            return """{"type":"TurnInfo","event":"$event","turn_index":0,"audio_window_start":0.0,"audio_window_end":1.0,""" +
                """"transcript":"$transcript","words":$words,"languages":[$langs],"end_of_turn_confidence":0.5,"sequence_id":1}"""
        }
    }
}
