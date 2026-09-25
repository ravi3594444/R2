package ai.wakey.android.agent

import ai.wakey.android.config.WakeySettings
import ai.wakey.android.llm.Decision
import ai.wakey.android.llm.DecisionException
import ai.wakey.android.llm.ChatModel
import ai.wakey.android.llm.ChatRequest
import ai.wakey.android.llm.DecisionModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val JEV_MS = 150L
private const val LLM_MS = 1_000L

/** Answers like [model] after a realistic LLM delay, so a fast decision can win the race (virtual time). */
private class SlowModel(private val model: ScriptedModel) : ChatModel {
    override suspend fun complete(request: ChatRequest) = run {
        delay(LLM_MS)
        model.complete(request)
    }

    override suspend fun testConnection() = "OK"
}

/** Answers each decision with the next scripted (choice, confidence); records the options offered. */
private class ScriptedDecisions(vararg answers: Pair<String, Double>, private val fail: Boolean = false) : DecisionModel {
    private val queue = ArrayDeque(answers.toList())
    val asked = mutableListOf<Map<String, String>>()

    override suspend fun choose(state: String, instructions: String, options: Map<String, String>): Decision {
        asked += options
        delay(JEV_MS)
        if (fail) throw DecisionException("unreachable")
        val (choice, confidence) = queue.removeFirstOrNull() ?: ("unsure" to 1.0)
        return Decision(choice, confidence, emptyMap(), 150)
    }

    override suspend fun testConnection() = "OK"
}

class FastDecisionLoopTest {
    private val settingsMain = FakeUi("com.android.settings", "Settings", listOf("Search settings", "Network & internet", "Bluetooth", "Apps"))
    private val bluetooth = FakeUi(
        "com.android.settings", "Settings",
        listOf("Bluetooth", "Use Bluetooth", "Pair new device", "Device name"), texts = setOf("Bluetooth"),
    )
    private val chat = FakeUi("com.whatsapp", "WhatsApp", listOf("Priya", "Message", "Send"), texts = setOf("Priya"))

    private fun loop(model: ScriptedModel, screen: FakeScreen, decisions: DecisionModel, apps: AppLauncher = FakeApps(screen, emptyMap())) =
        AgentLoop(SlowModel(model), apps, { screen }, { WakeySettings() }, { 1_000L }, 120_000L, { decisions })

    @Test
    fun routineNavigationNeedsNoLlmCall() = runTest {
        val screen = FakeScreen(settingsMain).apply { transitions["Bluetooth"] = bluetooth }
        val apps = FakeApps(screen, mapOf("Settings" to settingsMain))
        val decisions = ScriptedDecisions("tap_3" to 0.82, "done" to 0.91)

        val result = loop(ScriptedModel.of(), screen, decisions, apps).run("open Settings and find Bluetooth", RecordingListener())

        assertEquals(AgentStatus.Completed, result.status)
        assertEquals(0, result.llmCalls)
        assertEquals(2, result.decisionCalls)
        assertEquals("Done. Bluetooth is open.", result.reply)
        assertEquals(listOf("Settings"), apps.opened)
        assertEquals(listOf("tap Bluetooth"), screen.log)
    }

    @Test
    fun unsureDecisionFallsBackToTheLlm() = runTest {
        val screen = FakeScreen(settingsMain).apply { transitions["Bluetooth"] = bluetooth }
        val model = ScriptedModel.of(toolCall("tap", """{"element_id":3}"""), toolCall("finish", """{"reply":"Bluetooth is open."}"""))

        val result = loop(model, screen, ScriptedDecisions("tap_2" to 0.4, "done" to 0.95)).run("find bluetooth", RecordingListener())

        assertEquals(listOf("tap Bluetooth"), screen.log)
        assertEquals("Done. Bluetooth is open.", result.reply)
        assertEquals(1, result.llmCalls)
        assertEquals(1, result.decisionCalls)
    }

    @Test
    fun decisionErrorsSwitchTheRunToTheLlm() = runTest {
        val screen = FakeScreen(settingsMain).apply { transitions["Bluetooth"] = bluetooth }
        val decisions = ScriptedDecisions(fail = true)
        val model = ScriptedModel.of(toolCall("tap", """{"element_id":3}"""), toolCall("finish", """{"reply":"Bluetooth is open."}"""))

        val result = loop(model, screen, decisions).run("find bluetooth", RecordingListener())

        assertEquals(1, decisions.asked.size)
        assertEquals(2, result.llmCalls)
        assertEquals("Bluetooth is open.", result.reply)
    }

    @Test
    fun llmAnswerThatArrivesFirstWins() = runTest {
        val screen = FakeScreen(settingsMain).apply { transitions["Bluetooth"] = bluetooth }
        val slowJev = object : DecisionModel {
            override suspend fun choose(state: String, instructions: String, options: Map<String, String>): Decision {
                delay(LLM_MS * 3)
                return Decision("tap_2", 0.99, emptyMap(), 3_000)
            }

            override suspend fun testConnection() = "OK"
        }
        val model = ScriptedModel.of(toolCall("tap", """{"element_id":3}"""), toolCall("finish", """{"reply":"Bluetooth is open."}"""))

        val result = loop(model, screen, slowJev).run("find bluetooth", RecordingListener())

        assertEquals(listOf("tap Bluetooth"), screen.log)
        assertEquals(0, result.decisionCalls)
        assertEquals(2, result.llmCalls)
    }

    @Test
    fun questionsSkipTheFastModel() = runTest {
        val screen = FakeScreen(settingsMain)
        val decisions = ScriptedDecisions("done" to 1.0)

        val result = loop(ScriptedModel.of(textReply("Paris.")), screen, decisions).run("what's the capital of France?", RecordingListener())

        assertTrue(decisions.asked.isEmpty())
        assertEquals("Paris.", result.reply)
    }

    @Test
    fun fastSendTapStillNeedsConfirmation() = runTest {
        val screen = FakeScreen(chat)
        val listener = RecordingListener(approve = false)
        val model = ScriptedModel.of(toolCall("finish", """{"reply":"Okay, not sent."}"""))

        loop(model, screen, ScriptedDecisions("tap_3" to 0.95)).run("send the message to Priya", listener)

        assertEquals("Tap “Send” in WhatsApp?", listener.confirmations.single().question)
        assertTrue(screen.log.isEmpty())
    }

    @Test
    fun fastDoneIsRefusedWhileTheTargetIsOnlyListed() = runTest {
        val screen = FakeScreen(settingsMain).apply { transitions["Bluetooth"] = bluetooth }
        val model = ScriptedModel.of(toolCall("tap", """{"element_id":3}"""), toolCall("finish", """{"reply":"Bluetooth is open."}"""))

        val result = loop(model, screen, ScriptedDecisions("done" to 0.99, "done" to 0.99)).run("open Settings and find Bluetooth", RecordingListener())

        assertEquals(listOf("tap Bluetooth"), screen.log)
        assertEquals(AgentStatus.Completed, result.status)
    }
}

class FastSearchLoopTest {
    private val home = FakeUi(
        "com.instagram.android", "Instagram",
        listOf("Instagram", "Your story", "Home", "Search and explore", "Reels", "Profile"), texts = setOf("Instagram"),
    )
    private val results = FakeUi("com.instagram.android", "Instagram", listOf("Search", "Accounts", "cats_of_instagram", "#cats"), texts = setOf("Accounts"))

    @Test
    fun fastSearchEndsWhenTheResultsShowTheQuery() = runTest {
        val screen = FakeScreen(home).apply { onText = { ui = results } }
        val apps = FakeApps(screen, mapOf("Instagram" to home))
        val decisions = ScriptedDecisions("search" to 0.75)
        val loop = AgentLoop(SlowModel(ScriptedModel.of()), apps, { screen }, { WakeySettings() }, { 1_000L }, 120_000L, { decisions })

        val result = loop.run("open Instagram and search for cats", RecordingListener())

        assertEquals(AgentStatus.Completed, result.status)
        assertEquals("Here are the results for cats.", result.reply)
        assertEquals(listOf("type cats"), screen.log)
        assertEquals(0, result.llmCalls)
    }
}
