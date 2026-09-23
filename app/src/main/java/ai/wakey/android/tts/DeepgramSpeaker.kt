package ai.wakey.android.tts

import ai.wakey.android.config.TtsEngine
import ai.wakey.android.config.WakeySettings
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Deepgram voices: Flux models on `/v2/speak`, Aura on `/v1/speak`. Each sentence is one batch request for raw
 * 24 kHz PCM16 that is streamed into an [AudioTrack] as it downloads, so speech starts before the download ends; the
 * next sentence is requested while the current one plays. Deepgram voices speak English only, so [speak] ignores
 * `languageTag` (Hindi text is routed to Android by [RoutingSpeaker]).
 */
class DeepgramSpeaker(
    http: OkHttpClient,
    private val apiKey: () -> String?,
    private val settings: () -> WakeySettings,
) : SpeechOutput {
    override val engine = TtsEngine.Deepgram

    // Tighter than the shared client's timeouts: a stalled request should fall back to Android quickly.
    private val client = http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val active = AtomicReference<Playback?>()

    /** Voices that answered SPEED_NOT_SUPPORTED; later requests for them omit `speed`. */
    private val speedUnsupported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** False when no API key is set, so a reply would fail without reaching the network. */
    internal val hasApiKey: Boolean get() = !apiKey().isNullOrBlank()

    override suspend fun speak(text: String, languageTag: String?, onStart: () -> Unit) =
        speakChunks(DeepgramTtsApi.chunks(text), onStart) {}

    /**
     * Speaks pre-split [chunks] in order. [onChunkQueued] receives each index once all of its audio is queued. When
     * speaking fails, queued audio is played out before the exception is thrown, so every reported chunk was heard.
     */
    internal suspend fun speakChunks(chunks: List<String>, onStart: () -> Unit, onChunkQueued: (Int) -> Unit) {
        if (chunks.isEmpty()) return
        val key = apiKey()?.trim().orEmpty()
        if (key.isEmpty()) throw SpeechOutputException("Add your Deepgram API key in Settings to use the Deepgram voice.")
        val s = settings()
        val model = s.deepgramVoice.ifBlank { WakeySettings.DEFAULT_DEEPGRAM_VOICE }
        val playback = Playback(key, model, DeepgramTtsApi.speedParam(s.speechRate), chunks, onStart, onChunkQueued)
        active.getAndSet(playback)?.abort()
        try {
            coroutineScope {
                val playing = async(Dispatchers.IO) { playback.play() }
                try {
                    playing.await()
                } finally {
                    // Blocking socket reads and AudioTrack writes ignore cancellation; aborting unblocks them at once.
                    if (!playing.isCompleted) playback.abort()
                }
            }
        } finally {
            active.compareAndSet(playback, null)
        }
    }

    override fun stop() {
        active.get()?.abort()
    }

    override fun release() = stop()

    /** One reply's requests and audio track. [play] blocks on an IO thread; [abort] may be called from any thread. */
    private inner class Playback(
        private val key: String,
        private val model: String,
        private val speed: String?,
        private val chunks: List<String>,
        private val onStart: () -> Unit,
        private val onChunkQueued: (Int) -> Unit,
    ) {
        private val lock = Any()
        private val requests = CopyOnWriteArrayList<PendingResponse>()
        private var track: AudioTrack? = null
        @Volatile private var aborted = false
        private var framesWritten = 0L
        private var started = false

        /** Silences playback immediately and cancels every request; [play] then returns normally. */
        fun abort() {
            synchronized(lock) {
                if (aborted) return
                aborted = true
                track?.let {
                    it.pause()
                    it.flush()
                    it.stop() // wakes a write() blocked on a full buffer
                }
            }
            requests.forEach(PendingResponse::cancel)
        }

        /** Plays every chunk, then waits for the audio to finish. Returns normally when aborted. */
        fun play() {
            try {
                val out = openTrack() ?: return
                try {
                    playChunks(out)
                } catch (e: Exception) {
                    // Let the listener hear what already arrived; the caller can then fall back for the rest.
                    if (!aborted) drain(out)
                    throw e
                }
            } catch (e: Exception) {
                if (aborted) return
                throw when (e) {
                    is SpeechOutputException -> e
                    is IOException -> SpeechOutputException(DeepgramTtsApi.networkErrorMessage(e), e)
                    else -> SpeechOutputException("The Deepgram voice failed (${e.javaClass.simpleName}).", e)
                }
            } finally {
                requests.forEach(PendingResponse::discard)
                synchronized(lock) {
                    track?.release()
                    track = null
                }
            }
        }

        private fun playChunks(out: AudioTrack) {
            var pending = request(0)
            for (i in chunks.indices) {
                val response = successfulResponse(pending, i) ?: return
                if (i + 1 < chunks.size) pending = request(i + 1)
                response.use { stream(it, out) }
                if (aborted) return
                onChunkQueued(i)
            }
            drain(out)
        }

        private fun request(index: Int): PendingResponse {
            val speed = speed?.takeUnless { model in speedUnsupported }
            val call = client.newCall(
                Request.Builder()
                    .url(DeepgramTtsApi.speakUrl(model, speed))
                    .header("Authorization", "Token $key")
                    .post(DeepgramTtsApi.requestBody(chunks[index]))
                    .build(),
            )
            val pending = PendingResponse(call, sentSpeed = speed != null)
            requests += pending
            if (aborted) call.cancel()
            call.enqueue(pending)
            return pending
        }

        /** Awaits [pending]; retries once without `speed` if the voice rejects it. Null when aborted meanwhile. */
        private fun successfulResponse(pending: PendingResponse, index: Int): Response? {
            val response = pending.await()
            if (aborted) {
                response.close()
                return null
            }
            if (response.isSuccessful) {
                val type = response.header("Content-Type").orEmpty()
                if (type.startsWith("audio/l16")) return response
                response.close()
                throw SpeechOutputException("Deepgram sent audio in an unexpected format ($type).")
            }
            val body = response.use { it.peekBody(ERROR_BODY_BYTES).string() }
            if (pending.sentSpeed && DeepgramTtsApi.isSpeedUnsupported(response.code, body)) {
                speedUnsupported += model
                return successfulResponse(request(index), index)
            }
            throw SpeechOutputException(DeepgramTtsApi.httpErrorMessage(response.code, DeepgramTtsApi.errorCode(body)))
        }

        private fun stream(response: Response, track: AudioTrack) {
            val source = (response.body ?: throw IOException("Empty response")).source()
            val decoder = Pcm16Decoder()
            val bytes = ByteArray(READ_BYTES)
            val samples = ShortArray(READ_BYTES / 2 + 1)
            while (!aborted) {
                val read = source.read(bytes, 0, bytes.size)
                if (read < 0) return
                write(track, samples, decoder.decode(bytes, read, samples))
            }
        }

        private fun write(track: AudioTrack, samples: ShortArray, count: Int) {
            var offset = 0
            while (offset < count && !aborted) {
                val written = track.write(samples, offset, count - offset)
                if (written < 0) throw SpeechOutputException("Audio output failed (AudioTrack error $written).")
                offset += written
            }
            framesWritten += count
            if (count > 0 && !started) {
                started = true
                onStart()
            }
        }

        /**
         * Waits until everything written has played. Trailing silence lets a reply shorter than the start threshold
         * begin playing, and gives the output path time to sound the last word before the track is released.
         */
        private fun drain(track: AudioTrack) {
            if (framesWritten == 0L) return
            write(track, ShortArray(START_THRESHOLD_FRAMES), START_THRESHOLD_FRAMES)
            var lastHead = -1L
            var lastMove = System.nanoTime()
            while (!aborted) {
                val head = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                if (head >= framesWritten) return
                val now = System.nanoTime()
                if (head != lastHead) {
                    lastHead = head
                    lastMove = now
                } else if (now - lastMove > STALL_NANOS) {
                    return
                }
                Thread.sleep(DRAIN_POLL_MS)
            }
        }

        private fun openTrack(): AudioTrack? {
            val format = AudioFormat.Builder()
                .setSampleRate(DeepgramTtsApi.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBytes = AudioTrack.getMinBufferSize(
                DeepgramTtsApi.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(SpeechAudioFocus.SPEECH_ATTRIBUTES)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBytes, BUFFER_BYTES))
                    .build()
            } catch (e: UnsupportedOperationException) {
                throw SpeechOutputException("Could not open the audio output.", e)
            }
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                throw SpeechOutputException("Could not open the audio output.")
            }
            synchronized(lock) {
                if (aborted) {
                    track.release()
                    return null
                }
                this.track = track
            }
            // The default start threshold is the whole buffer; start as soon as a little audio is queued.
            track.setStartThresholdInFrames(START_THRESHOLD_FRAMES)
            track.play()
            return track
        }
    }

    /** An enqueued request whose response is awaited from a blocking thread. */
    private class PendingResponse(private val call: Call, val sentSpeed: Boolean) : Callback {
        private val result = CompletableFuture<Response>()

        override fun onResponse(call: Call, response: Response) {
            result.complete(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            result.completeExceptionally(e)
        }

        /** Blocks until the response headers arrive; the caller closes the response. */
        fun await(): Response = try {
            result.get()
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: IOException(e.cause)
        }

        fun cancel() = call.cancel()

        /** Cancels the request and closes its response, now or when it arrives; closing a read response is harmless. */
        fun discard() {
            call.cancel()
            result.thenAccept(Response::close)
        }
    }

    companion object {
        /** Deepgram voices for the picker: Indian English first, then featured Flux voices and one Aura-2 voice. */
        val VOICES: List<VoiceOption> = listOf(
            voice("flux-meena-en", "Meena · Indian English · female", "en-IN"),
            voice("flux-priya-en", "Priya · Indian English · female", "en-IN"),
            voice("flux-naveen-en", "Naveen · Indian English · male", "en-IN"),
            voice("flux-hannah-en", "Hannah · American English · female", "en-US"),
            voice("flux-alexis-en", "Alexis · American English · female", "en-US"),
            voice("flux-cole-en", "Cole · American English · male", "en-US"),
            voice("flux-sienna-en", "Sienna · American English · female", "en-US"),
            voice("flux-kit-en", "Kit · British English · male", "en-GB"),
            voice("aura-2-thalia-en", "Thalia · American English · female · Aura-2", "en-US"),
        )

        private fun voice(id: String, label: String, languageTag: String) =
            VoiceOption(id, label, languageTag, offline = false, engine = TtsEngine.Deepgram)

        private const val READ_BYTES = 4_096
        private const val ERROR_BODY_BYTES = 4_096L

        /** One second of audio: the network can run ahead of playback by this much. */
        private const val BUFFER_BYTES = DeepgramTtsApi.SAMPLE_RATE * 2

        /** 100 ms: playback starts once this much is queued, and drain pads with this much silence. */
        private const val START_THRESHOLD_FRAMES = DeepgramTtsApi.SAMPLE_RATE / 10
        private const val DRAIN_POLL_MS = 20L
        private const val STALL_NANOS = 1_000_000_000L
    }
}
