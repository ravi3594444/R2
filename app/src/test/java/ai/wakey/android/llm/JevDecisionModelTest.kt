package ai.wakey.android.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JevDecisionModelTest {
    @Test
    fun buildsOneChoiceQuestion() {
        val body = JSONObject(JevDecisionModel.requestBody("typesafe/jev", "{\"task\":\"x\"}", "Pick one", mapOf("a" to "A", "b" to "B")))
        assertEquals("typesafe/jev", body.getString("model"))
        val question = body.getJSONObject("questions").getJSONObject("next")
        assertEquals("choice", question.getString("type"))
        assertEquals("B", question.getJSONObject("criteria").getString("b"))
    }

    @Test
    fun parsesTheChoiceWithConfidence() {
        // Shape of a real AI/ML API reply.
        val reply = """{"model":"typesafe/jev-1.13-20260917","answers":{"next":{"type":"choice","choice":"tap_3","confidence":0.79,
            "probabilities":{"tap_3":0.82,"done":0.11,"go_back":0.04}}},"usage":{"input_tokens":717,"output_tokens":132}}"""
        val decision = JevDecisionModel.parseDecision(reply, setOf("tap_3", "done", "go_back"), 610)
        assertEquals("tap_3", decision.choice)
        assertEquals(0.79, decision.confidence, 1e-9)
        assertEquals(0.11, decision.probabilities.getValue("done"), 1e-9)
        assertEquals(610, decision.latencyMs)
    }

    @Test
    fun rejectsUnknownChoices() {
        val reply = """{"answers":{"next":{"type":"choice","choice":"launch_rockets","confidence":1}}}"""
        assertThrows(DecisionException::class.java) { JevDecisionModel.parseDecision(reply, setOf("a", "b"), 1) }
    }
}
