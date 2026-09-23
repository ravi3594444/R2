package ai.wakey.android.llm

import okhttp3.OkHttpClient

/**
 * OpenAI-compatible `/chat/completions` client (Fireworks by default) with tools and image input.
 * STUB: implemented by the agent module.
 */
class OpenAiCompatibleChatModel(
    private val http: OkHttpClient,
    private val baseUrl: () -> String,
    private val model: () -> String,
    private val apiKey: () -> String?,
) : ChatModel {
    override suspend fun complete(request: ChatRequest): ChatResponse = throw UnsupportedOperationException()
    override suspend fun testConnection(): String = throw UnsupportedOperationException()
}
