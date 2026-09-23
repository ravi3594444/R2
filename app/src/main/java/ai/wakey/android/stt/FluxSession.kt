package ai.wakey.android.stt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import java.util.concurrent.atomic.AtomicBoolean

/** The WebSocket as a [FluxSession] sees it; lets tests drive a session without a network. */
internal interface FluxSocket {
    /** Queues a frame without blocking. */
    fun sendBinary(bytes: ByteArray)

    /** Queues a frame without blocking. */
    fun sendText(text: String)

    /** Starts a normal closing handshake. */
    fun close()

    /** Drops the connection immediately. */
    fun cancel()

    /** Transport events, delivered one at a time. */
    interface Listener {
        fun onOpen()
        fun onText(text: String)

        /** The peer ended the connection, with a close frame or by ending the TCP stream. */
        fun onClosed(code: Int, reason: String)
        fun onFailure(error: SttError)
    }
}

/**
 * One Flux utterance. Audio pushes, control calls and socket events are funnelled into a serial
 * actor, so session state needs no locks and listener callbacks arrive one at a time, in order.
 *
 * Lifecycle: audio pushed while connecting is held (up to [MAX_PENDING_MS], oldest dropped) and
 * flushed on open; a connection not open within [CONNECT_TIMEOUT_MS] is a Network error. The first non-blank `EndOfTurn` is the final transcript; the session then sends
 * `CloseStream` and closes. [endTurn] and [close] flush the partial frame and send
 * `ForceEndTurn` + `CloseStream` (or just `CloseStream`). Flux then either answers with an
 * `EndOfTurn` or, when no turn was active yet (nothing said, or speech still being decoded), decodes
 * the remaining audio and hangs up; the latest turn update is then the final transcript, possibly
 * empty. Exactly one final transcript or error is reported, followed by one `onClosed`, unless the
 * session is cancelled.
 *
 * `transcriptionMs` maps the last word's end time (seconds on the stream's audio clock) to the
 * moment the 80 ms frame containing it was pushed to [sendPcm], and measures from there to the
 * arrival of the final message. Live microphone audio is pushed as it is captured, so this
 * approximates "user stopped talking → final" at frame resolution; for audio held while
 * connecting it includes the rest of the connection wait, which the user also experiences.
 */
internal class FluxSession(
    config: SttConfig,
    private val listener: SttListener,
    dispatcher: CoroutineDispatcher,
    private val nanoTime: () -> Long,
) : SttSession {
    private enum class Phase { Connecting, Streaming, Closing, Done }
    private enum class EndRequest { ForceEndTurn, CloseStream }

    private sealed interface Event {
        class Audio(val samples: ShortArray, val pushedAt: Long) : Event
        data object EndTurn : Event
        data object Close : Event
        class Opened(val at: Long) : Event
        class Message(val text: String, val at: Long) : Event
        class Closed(val code: Int, val reason: String, val at: Long) : Event
        class Failed(val error: SttError) : Event
        data object ConnectTimedOut : Event
        data object CloseTimedOut : Event
    }

    private class HeldFrame(val bytes: ByteArray, val pushedAt: Long)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val cancelled = AtomicBoolean(false)
    private val callbackLock = Any()

    @Volatile private var acceptingAudio = true

    @Volatile private var socket: FluxSocket? = null

    // Confined to the actor.
    private val frameSamples = config.sampleRate * CHUNK_MS / 1000
    private val chunker = PcmChunker(frameSamples)
    private val held = ArrayDeque<HeldFrame>()
    private val timeline = AudioTimeline(config.sampleRate, TIMELINE_MS / CHUNK_MS)
    private var phase = Phase.Connecting
    private var endRequest: EndRequest? = null
    private var terminal = false
    private var startedAt = 0L
    private var lastPushAt = 0L
    private var latest: FluxMessage.TurnInfo? = null
    private var lastPartial = ""
    private var closeTimeout: Job? = null

    private val socketListener = object : FluxSocket.Listener {
        override fun onOpen() {
            events.trySend(Event.Opened(nanoTime()))
        }

        override fun onText(text: String) {
            events.trySend(Event.Message(text, nanoTime()))
        }

        override fun onClosed(code: Int, reason: String) {
            events.trySend(Event.Closed(code, reason, nanoTime()))
        }

        override fun onFailure(error: SttError) {
            events.trySend(Event.Failed(error))
        }
    }

    /** Connects through [connect], which must return at once and report progress to its listener. */
    fun start(connect: (FluxSocket.Listener) -> FluxSocket) {
        startedAt = nanoTime()
        socket = connect(socketListener)
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            events.trySend(Event.ConnectTimedOut)
        }
        runActor()
    }

    /** Starts a session that reports [error] (asynchronously, like any other failure) and closes. */
    fun startFailed(error: SttError) {
        events.trySend(Event.Failed(error))
        runActor()
    }

    override fun sendPcm(samples: ShortArray, length: Int) {
        require(length in 0..samples.size) { "length $length outside 0..${samples.size}" }
        if (acceptingAudio && length > 0) events.trySend(Event.Audio(samples.copyOf(length), nanoTime()))
    }

    override fun endTurn() {
        events.trySend(Event.EndTurn)
    }

    override fun close() {
        events.trySend(Event.Close)
    }

    /**
     * Waits for a listener callback already in progress, so none runs after this returns; listener
     * callbacks therefore must not block on a thread that may call this.
     */
    override fun cancel() {
        synchronized(callbackLock) {
            if (!cancelled.compareAndSet(false, true)) return
        }
        acceptingAudio = false
        events.close()
        socket?.cancel()
        scope.cancel()
    }

    private fun runActor() {
        scope.launch { for (event in events) handle(event) }
    }

    private fun handle(event: Event) {
        if (phase == Phase.Done) return
        when (event) {
            is Event.Audio -> onAudio(event)
            Event.EndTurn -> requestEnd(EndRequest.ForceEndTurn)
            Event.Close -> requestEnd(EndRequest.CloseStream)
            is Event.Opened -> onOpened(event.at)
            is Event.Message -> onMessage(event.text, event.at)
            is Event.Closed -> onSocketClosed(event)
            is Event.Failed -> if (terminal) finish() else fail(event.error)
            Event.ConnectTimedOut -> if (phase == Phase.Connecting) {
                fail(SttError(SttError.Kind.Network, "Timed out connecting to Deepgram"))
            }
            Event.CloseTimedOut -> if (terminal) {
                transport.cancel()
                finish()
            } else {
                fail(SttError(SttError.Kind.Network, "Timed out waiting for Deepgram's final transcript"))
            }
        }
    }

    private fun onAudio(event: Event.Audio) {
        if (endRequest != null || terminal) return
        lastPushAt = event.pushedAt
        chunker.push(event.samples, event.samples.size) { frame ->
            if (phase == Phase.Streaming) {
                transmit(frame, event.pushedAt)
            } else {
                held.addLast(HeldFrame(frame, event.pushedAt))
                if (held.size > MAX_PENDING_MS / CHUNK_MS) held.removeFirst()
            }
        }
    }

    private fun onOpened(at: Long) {
        if (phase != Phase.Connecting) return
        phase = Phase.Streaming
        notify { onConnected((at - startedAt) / NANOS_PER_MS) }
        while (held.isNotEmpty()) held.removeFirst().let { transmit(it.bytes, it.pushedAt) }
        if (endRequest != null) endStream()
    }

    private fun requestEnd(request: EndRequest) {
        if (terminal || endRequest != null) return
        endRequest = request
        acceptingAudio = false
        if (phase == Phase.Streaming) endStream()
    }

    private fun endStream() {
        chunker.drain()?.let { transmit(it, lastPushAt) }
        if (endRequest == EndRequest.ForceEndTurn) transport.sendText(FORCE_END_TURN)
        transport.sendText(CLOSE_STREAM)
        phase = Phase.Closing
        armCloseTimeout()
    }

    private fun onMessage(text: String, at: Long) {
        if (terminal) return
        val message = try {
            FluxMessage.parse(text)
        } catch (e: JSONException) {
            fail(SttError(SttError.Kind.Protocol, "Unreadable message from Deepgram"))
            return
        }
        when (message) {
            is FluxMessage.TurnInfo -> onTurnInfo(message, at)
            is FluxMessage.FatalError -> fail(SttError(SttError.Kind.Server, message.readable))
            // Includes the Warning for a ForceEndTurn that found no active turn: CloseStream follows it.
            FluxMessage.Connected, is FluxMessage.Other -> Unit
        }
    }

    private fun onTurnInfo(info: FluxMessage.TurnInfo, at: Long) {
        if (info.event == TurnEvent.EndOfTurn) {
            if (info.transcript.isBlank()) {
                // Noise ended a turn without words: keep listening for the real one.
                latest = null
                lastPartial = ""
                return
            }
            deliverFinal(info, at)
            if (phase == Phase.Streaming) transport.sendText(CLOSE_STREAM)
            transport.close()
            phase = Phase.Closing
            armCloseTimeout()
            return
        }
        latest = info
        if (info.event == TurnEvent.StartOfTurn) notify { onSpeechStarted() }
        if (info.transcript.isNotBlank() && info.transcript != lastPartial) {
            lastPartial = info.transcript
            notify { onTranscript(info.transcript, isFinal = false, languages = info.languages) }
        }
    }

    private fun onSocketClosed(event: Event.Closed) {
        when {
            terminal -> finish()
            phase == Phase.Closing -> {
                deliverFinal(latest, event.at)
                finish()
            }
            else -> {
                val reason = event.reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                fail(SttError(SttError.Kind.Network, "Deepgram closed the connection (code ${event.code}$reason)"))
            }
        }
    }

    /** Reports [info] (null: nothing was said) as the session's one final transcript. */
    private fun deliverFinal(info: FluxMessage.TurnInfo?, at: Long) {
        terminal = true
        acceptingAudio = false
        val transcriptionMs = info?.lastWordEnd?.let(timeline::pushedAt)
            ?.let { pushedAt -> ((at - pushedAt) / NANOS_PER_MS).coerceAtLeast(0) }
        notify { onTranscript(info?.transcript.orEmpty(), isFinal = true, languages = info?.languages.orEmpty(), transcriptionMs) }
    }

    private fun fail(error: SttError) {
        terminal = true
        acceptingAudio = false
        notify { onError(error) }
        socket?.cancel()
        finish()
    }

    private fun finish() {
        phase = Phase.Done
        notify { onClosed() }
        events.close()
        scope.cancel()
    }

    private fun transmit(frame: ByteArray, pushedAt: Long) {
        transport.sendBinary(frame)
        timeline.record(frame.size / 2, pushedAt)
    }

    private fun armCloseTimeout() {
        if (closeTimeout != null) return
        closeTimeout = scope.launch {
            delay(CLOSE_TIMEOUT_MS)
            events.trySend(Event.CloseTimedOut)
        }
    }

    /** Only reached after [start] set the socket: socket events and flushes need a connection. */
    private val transport: FluxSocket get() = checkNotNull(socket)

    private inline fun notify(callback: SttListener.() -> Unit) {
        synchronized(callbackLock) {
            if (!cancelled.get()) listener.callback()
        }
    }

    internal companion object {
        const val CHUNK_MS = 80

        /** Bounds a stalled upgrade, which the client's read timeout alone could leave hanging. */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** Audio held while connecting; the oldest is dropped beyond this. */
        const val MAX_PENDING_MS = 15_000

        /** How far back word end times can be mapped to push times. */
        const val TIMELINE_MS = 120_000

        /** Flux hangs up ~100 ms after CloseStream; this bounds a stalled mobile link. */
        const val CLOSE_TIMEOUT_MS = 3_000L

        private const val NANOS_PER_MS = 1_000_000L
    }
}
