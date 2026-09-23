package ai.wakey.android.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatJsonTest {

    @Test
    fun mapsEveryMessageKind() {
        val request = ChatRequest(
            messages = listOf(
                ChatMessage.System("Be brief."),
                ChatMessage.User("open Settings"),
                ChatMessage.User("Screenshot", imageJpegBase64 = "QUJD"),
                ChatMessage.Assistant(null, listOf(ToolCall("call_1", "tap", """{"element_id":3}"""))),
                ChatMessage.Tool("call_1", "tap", "OK: Tapped."),
                ChatMessage.Assistant("Done."),
            ),
            tools = listOf(ToolSpec("tap", "Tap an element.", """{"type":"object","properties":{"element_id":{"type":"integer"}}}""")),
            maxTokens = 256,
            temperature = 0.1,
        )
        val body = ChatJson.requestBody(request, "accounts/x/models/m", "none")
        assertEquals("accounts/x/models/m", body.getString("model"))
        assertEquals(256, body.getInt("max_tokens"))
        assertEquals(0.1, body.getDouble("temperature"), 1e-9)
        assertEquals("none", body.getString("reasoning_effort"))
        assertEquals("auto", body.getString("tool_choice"))

        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("tap", tool.getJSONObject("function").getString("name"))
        assertEquals("integer", tool.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("element_id").getString("type"))

        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("Be brief.", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("open Settings", messages.getJSONObject(1).getString("content"))

        val parts = messages.getJSONObject(2).getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("Screenshot", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,QUJD", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))

        val assistant = messages.getJSONObject(3)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("", assistant.getString("content"))
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("function", call.getString("type"))
        assertEquals("tap", call.getJSONObject("function").getString("name"))
        assertEquals("""{"element_id":3}""", call.getJSONObject("function").getString("arguments"))

        assertEquals(
            JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "OK: Tapped.").toString(),
            messages.getJSONObject(4).toString(),
        )
        assertFalse(messages.getJSONObject(5).has("tool_calls"))
    }

    @Test
    fun omitsToolsAndReasoningEffortWhenAbsent() {
        val body = ChatJson.requestBody(ChatRequest(listOf(ChatMessage.User("hi"))), "m", null)
        assertFalse(body.has("tools"))
        assertFalse(body.has("tool_choice"))
        assertFalse(body.has("reasoning_effort"))
    }

    @Test
    fun parsesAFireworksToolCallResponse() {
        // Recorded shape: empty content, reasoning_content, arguments as a JSON string, "name": null on the call.
        val body = """
            {"id":"chatcmpl-1","object":"chat.completion",
             "choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"The user wants YouTube.",
               "tool_calls":[{"index":0,"id":"chatcmpl-tool-bb1e","type":"function",
                 "function":{"name":"open_app","arguments":"{\"name\": \"YouTube\"}"},"name":null}],"tools":null},
               "finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":388,"total_tokens":426,"completion_tokens":38}}
        """.trimIndent()
        val response = ChatJson.parseResponse(body, latencyMs = 835)
        assertEquals("", response.text)
        assertEquals(listOf(ToolCall("chatcmpl-tool-bb1e", "open_app", """{"name": "YouTube"}""")), response.toolCalls)
        assertEquals("tool_calls", response.finishReason)
        assertEquals(388, response.promptTokens)
        assertEquals(38, response.completionTokens)
        assertEquals(835, response.latencyMs)
    }

    @Test
    fun toleratesProviderVariations() {
        val body = """
            {"choices":[{"message":{"content":[{"type":"text","text":"<think>hmm</think>Hello "},{"type":"text","text":"there"}],
              "tool_calls":[{"function":{"name":"finish","arguments":{"reply":"Hi"}}},{"function":{"arguments":"{}"}}]},
              "finish_reason":null}]}
        """.trimIndent()
        val response = ChatJson.parseResponse(body, 1)
        assertEquals("Hello there", response.text)
        assertEquals(listOf(ToolCall("call_0", "finish", """{"reply":"Hi"}""")), response.toolCalls)
        assertNull(response.finishReason)
        assertEquals(0, response.promptTokens)
    }

    @Test
    fun reportsUnusableResponses() {
        expectBadResponse("<html>502</html>")
        val noChoices = expectBadResponse("""{"error":{"message":"model overloaded"}}""")
        assertTrue(noChoices.message!!.contains("model overloaded"))
    }

    @Test
    fun extractsProviderErrorMessages() {
        assertEquals("Invalid API key", ChatJson.errorMessage("""{"error":{"message":"Invalid API key","code":401}}"""))
        assertEquals("bad", ChatJson.errorMessage("""{"error":"bad"}"""))
        assertEquals("Model not found", ChatJson.errorMessage("""{"detail":"Model not found"}"""))
        assertEquals("Gateway timeout", ChatJson.errorMessage("Gateway timeout"))
        assertNull(ChatJson.errorMessage(""))
    }

    private fun expectBadResponse(body: String): LlmException {
        try {
            ChatJson.parseResponse(body, 0)
        } catch (e: LlmException) {
            assertEquals(LlmException.Kind.BadResponse, e.kind)
            return e
        }
        fail("Expected BadResponse")
        throw AssertionError()
    }
}
