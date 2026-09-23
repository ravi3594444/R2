package ai.wakey.android.llm

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** OpenAI `/chat/completions` wire format: request bodies, responses and provider error messages. */
internal object ChatJson {

    fun requestBody(request: ChatRequest, model: String, reasoningEffort: String?): JSONObject =
        JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { request.messages.forEach { put(message(it)) } })
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().apply { request.tools.forEach { put(tool(it)) } })
                put("tool_choice", "auto")
            }
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature)
            if (reasoningEffort != null) put("reasoning_effort", reasoningEffort)
        }

    fun message(message: ChatMessage): JSONObject = when (message) {
        is ChatMessage.System -> JSONObject().put("role", "system").put("content", message.text)
        is ChatMessage.User -> JSONObject().put("role", "user").put(
            "content",
            if (message.imageJpegBase64 == null) {
                message.text
            } else {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", message.text))
                    .put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", "data:image/jpeg;base64,${message.imageJpegBase64}"),
                        ),
                    )
            },
        )
        is ChatMessage.Assistant -> JSONObject().put("role", "assistant").apply {
            put("content", message.text.orEmpty())
            if (message.toolCalls.isNotEmpty()) {
                put("tool_calls", JSONArray().apply {
                    message.toolCalls.forEach { call ->
                        put(
                            JSONObject().put("id", call.id).put("type", "function").put(
                                "function",
                                JSONObject().put("name", call.name).put("arguments", call.argumentsJson),
                            ),
                        )
                    }
                })
            }
        }
        is ChatMessage.Tool -> JSONObject()
            .put("role", "tool")
            .put("tool_call_id", message.toolCallId)
            .put("content", message.content)
    }

    private fun tool(spec: ToolSpec): JSONObject = JSONObject().put("type", "function").put(
        "function",
        JSONObject()
            .put("name", spec.name)
            .put("description", spec.description)
            .put("parameters", JSONObject(spec.parametersJsonSchema)),
    )

    /** Parses a successful response. `reasoning_content` is deliberately ignored. */
    fun parseResponse(body: String, latencyMs: Long): ChatResponse {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw LlmException(LlmException.Kind.BadResponse, "The AI service sent a response that isn't JSON.", e)
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw LlmException(
                LlmException.Kind.BadResponse,
                errorMessage(body)?.let { "The AI service returned no answer: $it" } ?: "The AI service returned no answer.",
            )
        val message = choice.optJSONObject("message") ?: JSONObject()
        val calls = message.optJSONArray("tool_calls") ?: JSONArray()
        val toolCalls = (0 until calls.length()).mapNotNull { index ->
            val call = calls.optJSONObject(index) ?: return@mapNotNull null
            val function = call.optJSONObject("function") ?: return@mapNotNull null
            val name = function.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val arguments = when (val raw = function.opt("arguments")) {
                null, JSONObject.NULL -> "{}"
                is String -> raw
                else -> raw.toString() // some providers send the arguments as an object
            }
            ToolCall(call.optString("id").ifBlank { "call_$index" }, name, arguments)
        }
        val usage = root.optJSONObject("usage")
        return ChatResponse(
            text = content(message.opt("content")),
            toolCalls = toolCalls,
            finishReason = choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" },
            promptTokens = usage?.optInt("prompt_tokens") ?: 0,
            completionTokens = usage?.optInt("completion_tokens") ?: 0,
            latencyMs = latencyMs,
        )
    }

    /** Text content, which may be a string or an array of parts; an inline `<think>` block is dropped. */
    private fun content(raw: Any?): String? {
        val text = when (raw) {
            is String -> raw
            is JSONArray -> (0 until raw.length()).joinToString("") { raw.optJSONObject(it)?.optString("text").orEmpty() }
            else -> return null
        }
        return THINK_BLOCK.replace(text, "").trim()
    }

    /** The provider's human-readable error, from the usual `{"error": {"message": …}}` shapes. */
    fun errorMessage(body: String): String? {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return body.trim().take(MAX_ERROR_CHARS).ifEmpty { null }
        }
        val error = root.opt("error")
        val message = when (error) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> root.optString("message").ifEmpty { root.optString("detail") }
        }
        return message.trim().take(MAX_ERROR_CHARS).ifEmpty { null }
    }

    private val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private const val MAX_ERROR_CHARS = 300
}
