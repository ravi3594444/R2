package ai.wakey.android.tts

import ai.wakey.android.config.TtsEngine
import ai.wakey.android.config.WakeySettings
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks with the engine chosen in settings. Deepgram has no Hindi voice, so text containing Devanagari goes to
 * Android TTS (romanised Hinglish stays on the Deepgram voice). When Deepgram fails, the unheard rest of the reply is
 * spoken by Android and Deepgram is skipped for a minute, so a dead network costs one wait rather than one per reply.
 */
class RoutingSpeaker(
    val android: AndroidSpeaker,
    val deepgram: DeepgramSpeaker,
    private val settings: () -> WakeySettings,
) : SpeechOutput {
    override val engine: TtsEngine get() = settings().ttsEngine

    private val router = SpeechRouter(SystemClock::elapsedRealtime)

    /**
     * Why the latest reply was spoken by the Android voice although Deepgram is selected (Hindi text, no key, a
     * Deepgram failure or the retry pause after one), or null when there was no substitution. For the UI.
     */
    @Volatile var lastFallbackReason: String? = null
        private set

    override suspend fun speak(text: String, languageTag: String?, onStart: () -> Unit) {
        val started = AtomicBoolean()
        val onStartOnce = { if (started.compareAndSet(false, true)) onStart() }
        android.audioFocus.hold(onLoss = ::stop) {
            when (val route = router.route(settings().ttsEngine, text, deepgram.hasApiKey)) {
                is Route.Android -> {
                    lastFallbackReason = route.reason
                    android.speak(text, languageTag, onStartOnce)
                }
                Route.Deepgram -> speakWithDeepgram(text, languageTag, onStartOnce)
            }
        }
    }

    private suspend fun speakWithDeepgram(text: String, languageTag: String?, onStart: () -> Unit) {
        val chunks = DeepgramTtsApi.chunks(text)
        val heard = AtomicInteger()
        try {
            deepgram.speakChunks(chunks, onStart) { heard.set(it + 1) }
            router.deepgramSucceeded()
            lastFallbackReason = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            val reason = e.message ?: "The Deepgram voice failed."
            router.deepgramFailed(reason)
            lastFallbackReason = reason
            val rest = chunks.drop(heard.get()).joinToString(" ")
            if (rest.isNotEmpty()) android.speak(rest, languageTag, onStart)
        }
    }

    override fun stop() {
        android.stop()
        deepgram.stop()
    }

    override fun release() {
        android.release()
        deepgram.release()
    }
}
