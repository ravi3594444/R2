package ai.wakey.android.stt

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Client control message: end the current turn now; Flux answers with `EndOfTurn` (trigger `manual`). */
internal const val FORCE_END_TURN = """{"type":"ForceEndTurn"}"""

/** Client control message: decode the audio received so far, then close the connection. */
internal const val CLOSE_STREAM = """{"type":"CloseStream"}"""

/** `TurnInfo.event` values of the Flux turn state machine. */
internal enum class TurnEvent { StartOfTurn, Update, EagerEndOfTurn, TurnResumed, EndOfTurn }

/** The server messages of `wss://api.deepgram.com/v2/listen` that the client acts on. */
internal sealed interface FluxMessage {
    /** Sent once, right after the upgrade. */
    data object Connected : FluxMessage

    data class TurnInfo(
        val event: TurnEvent,
        /** Everything said so far in the current turn. */
        val transcript: String,
        /** Detected languages, most frequent first (`flux-general-multi` only). */
        val languages: List<String>,
        /** End of the last word, in seconds of audio since the stream started; null without timings. */
        val lastWordEnd: Double?,
    ) : FluxMessage

    /** Flux's `Error` message; the server closes the connection after it. */
    data class FatalError(val code: String, val description: String) : FluxMessage {
        val readable: String get() = if (description.isBlank()) code else "$description ($code)"
    }

    /** Anything else (`Warning`, Configure acknowledgements, types added later). */
    data class Other(val type: String) : FluxMessage

    companion object {
        /** @throws JSONException if [text] is not a JSON object. */
        fun parse(text: String): FluxMessage {
            val json = JSONObject(text)
            return when (val type = json.stringOrEmpty("type")) {
                "Connected" -> Connected
                "TurnInfo" -> json.toTurnInfo() ?: Other(type)
                "Error" -> FatalError(json.stringOrEmpty("code"), json.stringOrEmpty("description"))
                else -> Other(type)
            }
        }

        private fun JSONObject.toTurnInfo(): TurnInfo? {
            val event = stringOrEmpty("event").let { name -> TurnEvent.entries.firstOrNull { it.name == name } }
                ?: return null
            val lastWordEnd = optJSONArray("words")?.objects()
                ?.mapNotNull { word -> word.optDouble("end").takeIf { it.isFinite() } }
                ?.maxOrNull()
            val languages = optJSONArray("languages")?.let { array ->
                (0 until array.length()).mapNotNull { i -> array.takeUnless { it.isNull(i) }?.optString(i) }
                    .filter { it.isNotBlank() }
            }.orEmpty()
            return TurnInfo(event, stringOrEmpty("transcript"), languages, lastWordEnd)
        }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

        // Android's org.json returns "null" from optString for JSON nulls; the reference library returns "".
        private fun JSONObject.stringOrEmpty(name: String): String = if (isNull(name)) "" else optString(name)
    }
}
