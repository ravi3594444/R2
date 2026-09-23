package ai.wakey.android.agent

import ai.wakey.android.accessibility.ElementTarget
import ai.wakey.android.accessibility.ScrollDirection
import ai.wakey.android.llm.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsTest {
    private val screen = observationOf(
        element(1, "Search settings"),
        element(2, "Network & internet"),
        element(3, "Bluetooth"),
        element(4, null, description = "More options"),
    )
    private val everything = AgentTools.allowed(ScreenAccess.Available)

    private fun validate(name: String, args: String, allowed: Set<String> = everything) =
        AgentTools.validate(ToolCall("id", name, args), allowed, screen)

    private fun valid(name: String, args: String) = (validate(name, args) as ToolValidation.Valid).action

    private fun error(name: String, args: String, allowed: Set<String> = everything) =
        (validate(name, args, allowed) as ToolValidation.Invalid).error

    @Test
    fun rejectsUnknownAndUnavailableTools() {
        assertTrue(error("swipe", "{}").startsWith("Unknown tool \"swipe\""))
        assertTrue(error("tap", """{"element_id":3}""", AgentTools.allowed(ScreenAccess.Unavailable)).contains("not available"))
        assertEquals(setOf("finish", "ask_user"), AgentTools.allowed(ScreenAccess.Locked))
    }

    @Test
    fun rejectsMalformedAndIncompleteArguments() {
        assertEquals("The arguments must be a JSON object.", error("tap", "{element_id: 3"))
        assertEquals("\"name\" is required.", error("open_app", "{}"))
        assertEquals("\"name\" is required.", error("open_app", """{"name":"  "}"""))
        assertEquals("\"reply\" is required.", error("finish", """{"reply":null}"""))
        assertEquals("\"text\" is required.", error("enter_text", """{"element_id":1}"""))
        assertEquals("tap needs element_id or label.", error("tap", "{}"))
        assertEquals("direction must be up, down, left or right.", error("scroll", """{"direction":"sideways"}"""))
        assertEquals("\"element_id\" must be a whole number.", error("tap", """{"element_id":2.5}"""))
    }

    @Test
    fun elementsMustBeOnTheLatestScreen() {
        assertTrue(error("tap", """{"element_id":9}""").startsWith("Element [9] is not on the current screen"))
        assertTrue(error("scroll", """{"direction":"down","element_id":9}""").startsWith("Element [9]"))
        assertTrue(error("tap", """{"label":"Wi-Fi"}""").startsWith("No element labelled \"Wi-Fi\""))
        val noScreen = AgentTools.validate(ToolCall("id", "tap", """{"element_id":1}"""), everything, null)
        assertTrue(noScreen is ToolValidation.Invalid)
    }

    @Test
    fun acceptsLenientlyTypedArguments() {
        val byNumber = valid("tap", """{"element_id":3}""") as AgentAction.Tap
        assertEquals(ElementTarget(id = 3), byNumber.target)
        assertEquals("Bluetooth", byNumber.element?.text)
        assertEquals(ElementTarget(id = 3), (valid("tap", """{"element_id":"3"}""") as AgentAction.Tap).target)
        assertEquals(ElementTarget(id = 3), (valid("tap", """{"element_id":3.0}""") as AgentAction.Tap).target)

        val flagged = valid("tap", """{"element_id":3,"sensitive":"true","reason":" Pairing "}""") as AgentAction.Tap
        assertTrue(flagged.sensitive)
        assertEquals("Pairing", flagged.reason)

        val typed = valid("enter_text", """{"text":"bluetooth","element_id":1,"submit":"true"}""") as AgentAction.EnterText
        assertEquals(ElementTarget(id = 1), typed.target)
        assertTrue(typed.submit)
        assertEquals(null, (valid("enter_text", """{"text":""}""") as AgentAction.EnterText).target)

        assertEquals(ScrollDirection.Down, (valid("scroll", """{"direction":"DOWN"}""") as AgentAction.Scroll).direction)
        assertEquals(AgentAction.GoBack(false, null), valid("go_back", ""))
        assertEquals(AgentAction.ReadScreen, valid("read_screen", "{}"))
        assertEquals(AgentAction.Finish("Done."), valid("finish", """{"reply":" Done. "}"""))
    }

    @Test
    fun labelsResolveToElementIds() {
        val exact = valid("tap", """{"label":"bluetooth"}""") as AgentAction.Tap
        assertEquals(ElementTarget(id = 3, label = "bluetooth"), exact.target)
        val partial = valid("tap", """{"label":"Network"}""") as AgentAction.Tap
        assertEquals(2, partial.target.id)
        val byDescription = valid("tap", """{"label":"more options"}""") as AgentAction.Tap
        assertEquals(4, byDescription.target.id)
    }

    @Test
    fun toolSchemasAreValidJson() {
        for (spec in AgentTools.specs) {
            val schema = JSONObject(spec.parametersJsonSchema)
            assertEquals(spec.name, "object", schema.getString("type"))
            val properties = schema.getJSONObject("properties")
            val required = schema.optJSONArray("required")
            for (i in 0 until (required?.length() ?: 0)) assertTrue(spec.name, properties.has(required!!.getString(i)))
            val isAction = spec.name in setOf("open_app", "tap", "enter_text", "scroll", "scroll_to", "tap_point", "go_back", "go_home")
            assertEquals(spec.name, isAction, properties.has("sensitive") && properties.has("reason"))
        }
        assertEquals(12, AgentTools.specs.size)
    }
}
