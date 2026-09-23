package ai.wakey.android.tts

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlin.math.roundToInt

/** Request building and error mapping for Deepgram batch TTS. Plain Kotlin so it is unit tested on the JVM. */
internal object DeepgramTtsApi {
    /** Raw mono PCM16 at this rate is requested and played. */
    const val SAMPLE_RATE = 24_000

    /** Documented per-request limit for Aura; Flux batch documents none, so the same bound is used for both. */
    const val MAX_TEXT_CHARS = 2_000

    /** Sentences shorter than this ride along with the next one instead of costing a request of their own. */
    const val MIN_REQUEST_CHARS = 24

    private val BASE_URL = "https://api.deepgram.com".toHttpUrl()
    private val JSON = "application/json".toMediaType()

    /** Sentence-sized request texts for [text]. */
    fun chunks(text: String): List<String> = SpeechText.split(text, MAX_TEXT_CHARS, MIN_REQUEST_CHARS)

    /**
     * `/v1/speak` for Aura models, `/v2/speak` for Flux. `container=none` is required for raw PCM: without it
     * linear16 arrives wrapped in a streaming WAV header.
     */
    fun speakUrl(model: String, speed: String?): HttpUrl = BASE_URL.newBuilder()
        .addPathSegments(if (model.startsWith("aura")) "v1/speak" else "v2/speak")
        .addQueryParameter("model", model)
        .addQueryParameter("encoding", "linear16")
        .addQueryParameter("sample_rate", SAMPLE_RATE.toString())
        .addQueryParameter("container", "none")
        .apply { if (speed != null) addQueryParameter("speed", speed) }
        .build()

    /** Deepgram `speed` (0.5–1.5 in 0.05 steps) for the app's speech rate, clamped to that range; null at normal pace. */
    fun speedParam(rate: Float): String? {
        val steps = (rate.coerceIn(0.5f, 1.5f) * 20).roundToInt()
        return if (steps == 20) null else String.format(Locale.ROOT, "%.2f", steps / 20.0)
    }

    fun requestBody(text: String): RequestBody = JSONObject().put("text", text).toString().toRequestBody(JSON)

    /** `err_code` (or `category` in Deepgram's newer error shape) from an error body. */
    fun errorCode(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return json.optString("err_code").ifEmpty { json.optString("category") }.ifEmpty { null }
    }

    fun isSpeedUnsupported(httpCode: Int, body: String): Boolean = httpCode == 400 && "SPEED_NOT_SUPPORTED" in body

    /** Readable message for a failed request; never includes the reply text or credentials. */
    fun httpErrorMessage(httpCode: Int, errorCode: String?): String = when (httpCode) {
        401, 403 -> "Deepgram rejected the API key (HTTP $httpCode)."
        402 -> "The Deepgram account has no credit left (HTTP 402)."
        429 -> "Deepgram is rate limiting speech requests (HTTP 429)."
        in 500..599 -> "Deepgram's speech service is unavailable (HTTP $httpCode)."
        else -> "Deepgram could not speak the reply (HTTP $httpCode${errorCode?.let { ", $it" }.orEmpty()})."
    }

    fun networkErrorMessage(e: IOException): String = when (e) {
        is UnknownHostException -> "No internet connection for the Deepgram voice."
        is SocketTimeoutException -> "Deepgram did not respond in time."
        else -> "Lost the connection to Deepgram."
    }
}
