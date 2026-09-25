package ai.wakey.android.llm

/**
 * A fast "System One" decision model: given some state and a closed set of options it picks one,
 * with calibrated probabilities, and generates no text. The agent asks it first for routine steps
 * and falls back to the [ChatModel] when it is unsure.
 */
interface DecisionModel {
    /** [options] maps a stable key to a description of that option; the answer is one of the keys. */
    suspend fun choose(state: String, instructions: String, options: Map<String, String>): Decision

    /** A tiny bounded request that checks endpoint and key. Returns a readable summary. */
    suspend fun testConnection(): String

    /** Opens the connection ahead of a decision, in the background, so the decision doesn't wait for it. */
    fun warmUp() {}
}

data class Decision(
    val choice: String,
    val confidence: Double,
    val probabilities: Map<String, Double>,
    val latencyMs: Long,
)

class DecisionException(message: String, cause: Throwable? = null) : Exception(message, cause)
