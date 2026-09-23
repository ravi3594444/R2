package ai.wakey.android.tts

import ai.wakey.android.config.TtsEngine
import ai.wakey.android.config.WakeySettings
import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Android [TextToSpeech], preferring installed offline voices: Hindi for Devanagari text, otherwise Indian English,
 * then US or UK English. [WakeySettings.androidVoiceName] wins when it speaks the reply's language.
 *
 * The engine is bound on first use. [speak] suspends until the reply has been spoken; cancelling it, or [stop],
 * silences it at once.
 */
class AndroidSpeaker(private val context: Context, private val settings: () -> WakeySettings) : SpeechOutput {
    override val engine = TtsEngine.Android

    /** Also held by [RoutingSpeaker] around Deepgram replies. */
    internal val audioFocus = SpeechAudioFocus(context)

    private val bindLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val replies = ConcurrentHashMap<String, Reply>()
    private val replyIds = AtomicLong()

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var voices: List<Voice> = emptyList()
    @Volatile private var appliedVoice: String? = null
    @Volatile private var latestReply: String? = null

    /** One [speak] call; its text may span several utterances ("<reply id>#<index>"). */
    private class Reply(val lastUtterance: String, private val onStart: () -> Unit) {
        val done = CompletableDeferred<Unit>()
        private val started = AtomicBoolean()

        fun markStarted() {
            if (started.compareAndSet(false, true)) onStart()
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            replyFor(utteranceId)?.markStarted()
        }

        override fun onDone(utteranceId: String?) {
            replyFor(utteranceId)?.takeIf { it.lastUtterance == utteranceId }?.done?.complete(Unit)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            replyFor(utteranceId)?.done?.complete(Unit)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            replyFor(utteranceId)?.done?.completeExceptionally(SpeechOutputException(errorMessage(errorCode)))
        }

        @Deprecated("Superseded by onError(String, Int)", ReplaceWith("onError(utteranceId, TextToSpeech.ERROR)"))
        override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)
    }

    override suspend fun speak(text: String, languageTag: String?, onStart: () -> Unit) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val tts = boundEngine()
        useVoiceFor(tts, VoiceRanking.targetLanguage(clean, languageTag))
        tts.setSpeechRate(settings().speechRate)
        val chunks = SpeechText.split(clean, TextToSpeech.getMaxSpeechInputLength(), minChars = Int.MAX_VALUE)
        val id = "reply${replyIds.incrementAndGet()}"
        val reply = Reply("$id#${chunks.lastIndex}", onStart)
        audioFocus.hold(onLoss = ::stop) {
            // QUEUE_FLUSH silences any earlier reply; release its caller now in case the engine never reports onStop.
            replies.values.forEach { it.done.complete(Unit) }
            replies[id] = reply
            latestReply = id
            try {
                chunks.forEachIndexed { i, chunk ->
                    val queueMode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    if (tts.speak(chunk, queueMode, null, "$id#$i") != TextToSpeech.SUCCESS) {
                        throw SpeechOutputException("Android text-to-speech refused the reply.")
                    }
                }
                // Bounded: if the engine dies mid-utterance no callback ever arrives.
                withTimeoutOrNull(maxSpeechMs(text)) { reply.done.await() } ?: tts.stop()
            } catch (e: Exception) {
                // Includes cancellation: silence the rest of this reply unless a newer one has already replaced it.
                if (latestReply == id) tts.stop()
                throw e
            } finally {
                replies.remove(id)
            }
        }
    }

    override fun stop() {
        replies.values.forEach { it.done.complete(Unit) }
        tts?.stop()
    }

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
        voices = emptyList()
        appliedVoice = null
    }

    /**
     * Installed voices for Indian, US and UK English and Hindi, offline first. Empty until the engine has bound: the
     * first call starts binding, so a later call (for example when Settings is shown again) lists them.
     */
    fun availableVoices(): List<VoiceOption> {
        val bound = tts
        if (bound == null) {
            scope.launch {
                try {
                    boundEngine()
                } catch (e: SpeechOutputException) {
                    // Nothing to list; the next speak() reports the problem to the user.
                }
            }
            return emptyList()
        }
        voices = bound.voices.orEmpty().toList()
        return VoiceRanking.options(voices.map { it.info() })
    }

    private suspend fun boundEngine(): TextToSpeech = tts ?: bindLock.withLock { tts ?: bind().also { tts = it } }

    private suspend fun bind(): TextToSpeech {
        val status = CompletableDeferred<Int>()
        // onInit arrives on the main thread, which stays free because this coroutine suspends while waiting.
        val engine = withContext(Dispatchers.Main.immediate) { TextToSpeech(context) { status.complete(it) } }
        val result = try {
            withTimeoutOrNull(BIND_TIMEOUT_MS) { status.await() }
        } catch (e: CancellationException) {
            engine.shutdown()
            throw e
        }
        if (result != TextToSpeech.SUCCESS) {
            val hasEngine = engine.engines.isNotEmpty()
            engine.shutdown()
            throw SpeechOutputException(
                if (hasEngine) {
                    "Android text-to-speech did not start. Check the preferred engine in Android's text-to-speech settings."
                } else {
                    "No text-to-speech engine is installed. Install Speech Services by Google from the Play Store."
                },
            )
        }
        engine.setAudioAttributes(SpeechAudioFocus.SPEECH_ATTRIBUTES)
        engine.setOnUtteranceProgressListener(progress)
        voices = engine.voices.orEmpty().toList()
        return engine
    }

    private fun useVoiceFor(tts: TextToSpeech, target: String) {
        val preferred = settings().androidVoiceName
        // Voice data can be installed while the app runs, so re-read the list before giving up on a language.
        val voice = pickVoice(target, preferred) ?: run {
            voices = tts.voices.orEmpty().toList()
            pickVoice(target, preferred)
        }
        if (voice != null && (voice.name == appliedVoice || tts.setVoice(voice) == TextToSpeech.SUCCESS)) {
            appliedVoice = voice.name
            return
        }
        appliedVoice = null
        val result = tts.setLanguage(Locale.forLanguageTag(target))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw SpeechOutputException(
                if (target == VoiceRanking.HINDI) {
                    "No Hindi voice is installed. Add Hindi voice data in Android's text-to-speech settings."
                } else {
                    "No English voice is installed for Android text-to-speech."
                },
            )
        }
    }

    private fun pickVoice(target: String, preferred: String?): Voice? {
        val all = voices
        val chosen = VoiceRanking.select(all.map { it.info() }, target, preferred) ?: return null
        return all.firstOrNull { it.name == chosen.name }
    }

    private fun replyFor(utteranceId: String?): Reply? = utteranceId?.substringBefore('#')?.let(replies::get)

    private fun errorMessage(code: Int): String = when (code) {
        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT ->
            "The Android voice needs the internet. Pick an offline voice in Settings."
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "The Android voice is still downloading."
        TextToSpeech.ERROR_OUTPUT -> "Android text-to-speech could not play audio."
        else -> "Android text-to-speech failed (error $code)."
    }

    private fun Voice.info() = VoiceInfo(
        name = name,
        languageTag = locale.toLanguageTag(),
        quality = quality,
        latency = latency,
        networkRequired = isNetworkConnectionRequired,
        installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty(),
    )

    private companion object {
        /** Generous upper bound for one reply (~6 chars/s even at slow rates) plus engine start-up. */
        fun maxSpeechMs(text: String): Long = 10_000L + text.length * 170L

        const val BIND_TIMEOUT_MS = 6_000L
    }
}
