package ai.wakey.android.llm

/**
 * An OpenAI-style chat model with function calling. The app talks only to this interface so the
 * provider (or a backend proxy holding the credentials) can change without touching the agent.
 */
interface ChatModel {
    suspend fun complete(request: ChatRequest): ChatResponse

    /** A tiny, bounded request that checks endpoint, model id and key. Returns a readable summary. */
    suspend fun testConnection(): String

    /** Opens the connection ahead of a request, in the background, so the request doesn't wait for it. */
    fun warmUp() {}
}

data class ChatRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 1024,
    val temperature: Double = 0.2,
    /** Routes related requests to one replica so the provider can reuse the cached prompt prefix. */
    val sessionId: String? = null,
)

sealed class ChatMessage {
    data class System(val text: String) : ChatMessage()

    /** [imageJpegBase64] attaches a screenshot as an image_url data URL. */
    data class User(val text: String, val imageJpegBase64: String? = null) : ChatMessage()

    data class Assistant(val text: String?, val toolCalls: List<ToolCall> = emptyList()) : ChatMessage()
    data class Tool(val toolCallId: String, val name: String, val content: String) : ChatMessage()
}

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/** [parametersJsonSchema] is a JSON Schema object serialised as a string. */
data class ToolSpec(val name: String, val description: String, val parametersJsonSchema: String)

data class ChatResponse(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val promptTokens: Int,
    val completionTokens: Int,
    val latencyMs: Long,
)

class LlmException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { MissingKey, Auth, Network, RateLimit, Server, BadRequest, BadResponse }
}
