package ai.wakey.android.agent

import ai.wakey.android.llm.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastDecisionTest {
    private val settings = FakeUi("com.android.settings", "Settings", listOf("Search settings", "Network & internet", "Bluetooth", "Apps"))
        .observation()

    @Test
    fun extractsSearchQueries() {
        assertEquals("cats", FastDecision.searchQuery("open Instagram and search for cats"))
        assertEquals("lofi music", FastDecision.searchQuery("open YouTube and search lofi music"))
        assertEquals("pizza near me", FastDecision.searchQuery("search for pizza near me in Chrome"))
        assertEquals("cats", FastDecision.searchQuery("Instagram kholo aur cats search karo"))
        assertNull(FastDecision.searchQuery("open Settings and turn on Bluetooth"))
    }

    @Test
    fun onlyUiRequestsUseTheFastModel() {
        assertTrue(FastDecision.suitsGoal("open Settings and find Bluetooth"))
        assertTrue(FastDecision.suitsGoal("YouTube kholo aur gaana chalao"))
        assertFalse(FastDecision.suitsGoal("what's the capital of France?"))
        assertFalse(FastDecision.suitsGoal("tell me a joke"))
    }

    @Test
    fun planOffersTapsAndControlOptions() {
        val plan = FastDecision.plan("open Settings and find Bluetooth", settings, listOf("Opening Settings"), "fast_1")
        assertEquals(
            listOf("tap_1", "tap_2", "tap_3", "tap_4", "scroll_down", "go_back", "done", "unsure"),
            plan.options.keys.toList(),
        )
        assertEquals("Tap [3] button “Bluetooth”", plan.options["tap_3"])
        assertTrue(plan.state.contains("\"task\":\"open Settings and find Bluetooth\""))
        assertTrue(plan.state.contains("Opening Settings"))
    }

    @Test
    fun searchOptionTargetsTheSearchBox() {
        val plan = FastDecision.plan("open Settings and search for bluetooth", settings, emptyList(), "fast_1")
        val move = FastDecision.interpret(plan, Decision("search", 0.9, emptyMap(), 100)) as FastDecision.Move.Act
        val action = move.action as AgentAction.EnterText
        assertEquals("bluetooth", action.text)
        assertEquals(1, action.target?.id)
        assertTrue(action.submit)
        assertEquals("enter_text", move.call.name)
    }

    @Test
    fun lowConfidenceAndUnsureAreLeftToTheLlm() {
        val plan = FastDecision.plan("open Settings and find Bluetooth", settings, emptyList(), "fast_1")
        assertNull(FastDecision.interpret(plan, Decision("tap_3", 0.5, emptyMap(), 100)))
        assertNull(FastDecision.interpret(plan, Decision("unsure", 0.99, emptyMap(), 100)))
        assertNull(FastDecision.interpret(plan, Decision("done", 0.8, emptyMap(), 100)))
        val tap = FastDecision.interpret(plan, Decision("tap_3", 0.8, emptyMap(), 100)) as FastDecision.Move.Act
        assertEquals(3, (tap.action as AgentAction.Tap).target.id)
        assertEquals(FastDecision.Move.Done, FastDecision.interpret(plan, Decision("done", 0.9, emptyMap(), 100)))
    }
}
