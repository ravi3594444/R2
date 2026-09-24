package ai.wakey.android.llm

import ai.wakey.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * TypeSafe's Jev through AI/ML API's `POST /v1/decisions` (model `typesafe/jev`), asking a single
 * Choice question. Measured at ~0.6 s end to end (~0.2 s model time) against 1–3 s for an LLM step.
 */
class JevDecisionModel(
    http: OkHttpClient,
    private val baseUrl: () -> String,
    private val model: () -> String,
    private val apiKey: () -> String?,
) : DecisionModel {
    // A decision that is slower than an LLM call is worthless, so fail fast and let the LLM take over.
    private val client = http.newBuilder().callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS).build()

    override suspend fun choose(state: String, instructions: String, options: Map<String, String>): Decision {
        require(options.size >= 2) { "A choice needs at least two options." }
        val key = apiKey()?.trim().orEmpty()
        if (key.isEmpty()) throw DecisionException("Add your AI/ML API key in Settings to use Jev.")
        val url = (baseUrl().trim().trimEnd('/') + "/decisions").toHttpUrlOrNull()
            ?: throw DecisionException("The Jev base URL in Settings is not a valid http(s) URL.")
        val body = withContext(Dispatchers.Default) { requestBody(model().trim(), state, instructions, options) }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            // The gateway's bot filter rejects requests without a product user agent.
            .header("User-Agent", "Wakey/${BuildConfig.VERSION_NAME}")
            .post(body.toRequestBody(JSON))
            .build()
        val started = System.nanoTime()
        val (code, text) = execute(request)
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        if (code !in 200..299) throw DecisionException(errorMessage(code, text))
        return withContext(Dispatchers.Default) { parseDecision(text, options.keys, latencyMs) }
    }

    override suspend fun testConnection(): String {
        val decision = choose(
            state = "The user asked to turn the flashlight on. It is off.",
            instructions = "What should happen?",
            options = mapOf("turn_on" to "Turn the flashlight on", "nothing" to "Do nothing"),
        )
        return "OK · Jev chose ${decision.choice} in ${decision.latencyMs} ms"
    }

    private suspend fun execute(request: Request): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(DecisionException("Couldn't reach Jev: ${e.message ?: e.javaClass.simpleName}", e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { it.code to it.body?.string().orEmpty() }
                } catch (e: IOException) {
                    cont.resumeWithException(DecisionException("Jev's reply was cut off.", e))
                    return
                }
                cont.resume(result)
            }
        })
    }

    internal companion object {
        private const val CALL_TIMEOUT_S = 4L
        private const val QUESTION = "next"
        private val JSON = "application/json".toMediaType()

        fun requestBody(model: String, state: String, instructions: String, options: Map<String, String>): String {
            val criteria = JSONObject().apply { options.forEach { (key, description) -> put(key, description) } }
            val question = JSONObject().put("type", "choice").put("instructions", instructions).put("criteria", criteria)
            return JSONObject()
                .put("model", model)
                .put("state", state)
                .put("questions", JSONObject().put(QUESTION, question))
                .toString()
        }

        fun parseDecision(body: String, keys: Set<String>, latencyMs: Long): Decision {
            val answer = try {
                JSONObject(body).getJSONObject("answers").getJSONObject(QUESTION)
            } catch (e: JSONException) {
                throw DecisionException("Jev returned an unexpected response.", e)
            }
            val choice = answer.optString("choice")
            if (choice !in keys) throw DecisionException("Jev chose an unknown option.")
            val probabilities = answer.optJSONObject("probabilities")?.let { p ->
                p.keys().asSequence().associateWith { p.optDouble(it, 0.0) }
            }.orEmpty()
            val confidence = answer.optDouble("confidence", probabilities[choice] ?: 0.0)
            return Decision(choice, confidence, probabilities, latencyMs)
        }

        private fun errorMessage(code: Int, body: String): String {
            val detail = try {
                JSONObject(body).let { it.optJSONObject("error")?.optString("message") ?: it.optString("message") }
            } catch (_: JSONException) {
                null
            }?.takeIf { it.isNotBlank() }?.take(160)
            return when (code) {
                401, 403 -> "Jev rejected the AI/ML API key (HTTP $code)."
                429 -> "Jev is rate-limited right now (HTTP 429)."
                else -> "Jev failed (HTTP $code)${detail?.let { ": $it" }.orEmpty()}."
            }
        }
    }
}
