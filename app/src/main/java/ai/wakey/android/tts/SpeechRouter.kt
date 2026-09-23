package ai.wakey.android.tts

import ai.wakey.android.config.TtsEngine
import kotlin.math.ceil

/** Which engine speaks a reply. */
internal sealed interface Route {
    data object Deepgram : Route

    /** [reason] explains why Android substitutes for a selected Deepgram voice; null when Android was selected. */
    data class Android(val reason: String?) : Route
}

/**
 * Picks the engine for each reply. After a Deepgram failure, replies skip Deepgram for [cooldownMs] so a dead network
 * costs one wait rather than one per reply. [now] is a monotonic clock in milliseconds.
 */
internal class SpeechRouter(private val now: () -> Long, private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
    private var retryAt: Long? = null
    private var failure: String? = null

    @Synchronized
    fun route(selected: TtsEngine, text: String, deepgramHasKey: Boolean): Route {
        if (selected == TtsEngine.Android) return Route.Android(null)
        if (SpeechText.hasDevanagari(text)) return Route.Android(HINDI_REASON)
        if (!deepgramHasKey) return Route.Android(NO_KEY_REASON)
        val until = retryAt ?: return Route.Deepgram
        val remainingMs = until - now()
        if (remainingMs <= 0) return Route.Deepgram
        val seconds = ceil(remainingMs / 1000.0).toLong()
        return Route.Android("$failure Using the Android voice; retrying Deepgram in $seconds s.")
    }

    @Synchronized
    fun deepgramFailed(reason: String) {
        failure = reason
        retryAt = now() + cooldownMs
    }

    @Synchronized
    fun deepgramSucceeded() {
        failure = null
        retryAt = null
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 60_000L
        const val HINDI_REASON = "Deepgram has no Hindi voice, so Hindi text uses the Android voice."
        const val NO_KEY_REASON = "No Deepgram API key is set, so the Android voice is used."
    }
}
