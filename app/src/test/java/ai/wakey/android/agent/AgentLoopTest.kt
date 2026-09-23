package ai.wakey.android.agent

import ai.wakey.android.accessibility.ScreenshotResult
import ai.wakey.android.llm.ChatMessage
import ai.wakey.android.llm.LlmException
import ai.wakey.android.llm.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    private val launcher = FakeUi("com.google.android.apps.nexuslauncher", "Pixel Launcher", listOf("Chrome", "YouTube", "Settings"))
    private val settingsMain = FakeUi(
        "com.android.settings", "Settings",
        listOf("Search settings", "Network & internet", "Bluetooth", "Apps", "Battery"),
    )
    private val bluetooth = FakeUi(
        "com.android.settings", "Settings",
        listOf("Bluetooth", "Use Bluetooth", "Pair new device", "Device name"), texts = setOf("Bluetooth"),
    )
    private val chat = FakeUi("com.whatsapp", "WhatsApp", listOf("Priya", "Message", "Send"), texts = setOf("Priya"))

    @Test
    fun opensSettingsAndFindsBluetooth() = runTest {
        val screen = FakeScreen(launcher).apply { transitions["Bluetooth"] = bluetooth }
        val apps = FakeApps(screen, mapOf("Settings" to settingsMain))
        // "open Settings and …" opens Settings without a model call, so the model starts on Settings.
        val model = ScriptedModel.of(
            toolCall("tap", """{"element_id":3}"""),
            toolCall("finish", """{"reply":"Bluetooth settings are open."}"""),
        )
        val listener = RecordingListener()
        var now = 5_000L
        val result = agentLoop(model, screen, apps, clock = { now++ }).run("open Settings and find Bluetooth", listener)

        assertEquals(AgentStatus.Completed, result.status)
        assertEquals("Bluetooth settings are open.", result.reply)
        assertEquals(3, result.steps)
        assertEquals(2, result.llmCalls)
        assertEquals(200, result.promptTokens)
        assertEquals(20, result.completionTokens)
        assertEquals(5_000L, result.firstActionAtMs)
        assertEquals(listOf("Settings"), apps.opened)
        assertEquals(listOf("tap Bluetooth"), screen.log)

        assertEquals(
            listOf("open_app" to null, "open_app" to true, "tap" to null, "tap" to true),
            listener.actions.map { it.toolName to it.success },
        )
        assertEquals(listOf("Opening Settings", "Opening Settings", "Tapping “Bluetooth”", "Tapping “Bluetooth”"), listener.actions.map { it.description })
        assertEquals(listOf(1, 1, 2, 2), listener.actions.map { it.step })
        assertEquals(listOf("Looking at the screen", "Opening Settings", "Tapping “Bluetooth”"), screen.statuses)
        assertEquals(1, screen.statusHidden)

        // The first model request already carries the opened Settings screen as the open_app result.
        val first = model.requests[0]
        assertEquals(AgentTools.specs.map { it.name }, first.tools.map { it.name })
        assertTrue((first.messages[0] as ChatMessage.System).text.contains("exactly one tool per turn"))
        assertTrue(first.messages.any { it is ChatMessage.User && it.text.startsWith("Request: open Settings and find Bluetooth") })
        val opened = first.messages.last() as ChatMessage.Tool
        assertEquals("open_app", opened.name)
        assertTrue(opened.content.startsWith("OK: Opening Settings."))
        assertTrue(opened.content.contains("App: Settings"))

        // The tap's tool result shows the Bluetooth screen, and it is the only full screen left.
        val last = model.requests[1].messages
        val tapResult = last.last() as ChatMessage.Tool
        assertTrue(tapResult.content.startsWith("OK: Tapped “Bluetooth”."))
        assertTrue(tapResult.content.contains("[2] button \"Use Bluetooth\" (tap)"))
        assertEquals(1, last.count { it.text().contains("App: ") })
        assertTrue(last.any { it is ChatMessage.Tool && it.content == "OK: Opening Settings." })
        assertTrue(last.any { it is ChatMessage.User && it.text == "Request: open Settings and find Bluetooth\nReply language: English" })
    }

    @Test
    fun stopsAtTheStepLimit() = runTest {
        var page = 0
        val screen = FakeScreen(settingsMain)
        screen.onScroll = { screen.ui = settingsMain.copy(items = settingsMain.items + "Page ${++page}") }
        val model = ScriptedModel { _, _ -> toolCall("scroll", """{"direction":"down"}""") }

        val result = agentLoop(model, screen, maxSteps = 4).run("find the developer options", RecordingListener())

        assertEquals(AgentStatus.StepLimit, result.status)
        assertEquals(4, result.steps)
        assertEquals(4, result.llmCalls)
        assertEquals(List(4) { "scroll down" }, screen.log)
        assertTrue(result.reply.contains("4 steps"))
    }

    @Test
    fun stopsWhenTheScreenStopsChanging() = runTest {
        val screen = FakeScreen(settingsMain)
        val model = ScriptedModel { _, _ -> toolCall("tap", """{"element_id":4}""") }

        val result = agentLoop(model, screen).run("open app info", RecordingListener())

        assertEquals(AgentStatus.Failed, result.status)
        assertEquals(List(3) { "tap Apps" }, screen.log)
        assertEquals(3, result.llmCalls)
        assertTrue(result.reply.contains("screen stopped changing"))
    }

    @Test
    fun cancellingAfterAnActionStopsFurtherTaps() = runTest {
        val screen = FakeScreen(settingsMain).apply {
            transitions["Bluetooth"] = bluetooth
            transitions["Use Bluetooth"] = settingsMain
        }
        val model = ScriptedModel { index, _ -> toolCall("tap", """{"element_id":${if (index % 2 == 0) 3 else 2}}""") }
        val listener = RecordingListener()
        lateinit var job: Job
        // The user presses Stop as soon as the first tap has finished.
        listener.onActionHook = { if (it.result != null) job.cancel() }
        job = launch { agentLoop(model, screen).run("toggle bluetooth", listener) }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf("tap Bluetooth"), screen.log)
        assertEquals(1, model.requests.size)
        assertEquals(1, screen.statusHidden)
    }

    @Test
    fun cancellingDuringAModelCallStopsBeforeTheNextTap() = runTest {
        val screen = FakeScreen(settingsMain).apply { transitions["Bluetooth"] = bluetooth }
        val secondCallStarted = CompletableDeferred<Unit>()
        val model = ScriptedModel { index, _ ->
            if (index == 0) {
                toolCall("tap", """{"element_id":3}""")
            } else {
                secondCallStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch { agentLoop(model, screen).run("open bluetooth", RecordingListener()) }
        secondCallStarted.await()
        job.cancel()
        job.join()

        assertEquals(listOf("tap Bluetooth"), screen.log)
        assertEquals(1, screen.statusHidden)
    }

    @Test
    fun deniedSendIsNotExecuted() = runTest {
        val screen = FakeScreen(chat)
        val model = ScriptedModel.of(
            toolCall("tap", """{"element_id":3}"""),
            toolCall("finish", """{"reply":"Okay, I didn't send it."}"""),
        )
        val listener = RecordingListener(approve = false)

        val result = agentLoop(model, screen).run("send hi to Priya on WhatsApp", listener)

        assertEquals(AgentStatus.Completed, result.status)
        assertTrue(screen.log.isEmpty())
        assertEquals(1, listener.confirmations.size)
        assertEquals("Tap “Send” in WhatsApp?", listener.confirmations[0].question)
        assertTrue(listener.actions.isEmpty())
        assertNull(result.firstActionAtMs)
        val declined = model.requests[1].messages.last() as ChatMessage.Tool
        assertTrue(declined.content.contains("declined"))
    }

    @Test
    fun approvedSendIsExecuted() = runTest {
        val screen = FakeScreen(chat)
        val model = ScriptedModel.of(toolCall("tap", """{"element_id":3}"""), toolCall("finish", """{"reply":"Sent."}"""))
        val listener = RecordingListener(approve = true)

        agentLoop(model, screen).run("send hi to Priya on WhatsApp", listener)

        assertEquals(listOf("tap Send"), screen.log)
        assertEquals(1, listener.confirmations.size)
    }

    @Test
    fun modelFlaggedTextEntryAsksWithItsReason() = runTest {
        val screen = FakeScreen(chat)
        val model = ScriptedModel.of(
            toolCall("enter_text", """{"text":"running late","element_id":2,"submit":true,"sensitive":true,"reason":"Message Priya"}"""),
            toolCall("finish", """{"reply":"Okay."}"""),
        )
        val listener = RecordingListener(approve = false)

        agentLoop(model, screen).run("tell Priya I'm running late", listener)

        assertTrue(screen.log.isEmpty())
        val request = listener.confirmations.single()
        assertEquals("Type “running late” and submit it in WhatsApp?", request.question)
        assertEquals("Message Priya\nText: running late", request.detail)
    }

    @Test
    fun invalidToolCallsGetErrorsAndStopAfterThree() = runTest {
        val screen = FakeScreen(settingsMain)
        val model = ScriptedModel { _, _ -> toolCall("tap", """{"element_id":99}""") }

        val result = agentLoop(model, screen).run("open bluetooth", RecordingListener())

        assertEquals(AgentStatus.Failed, result.status)
        assertEquals(3, result.steps)
        assertTrue(screen.log.isEmpty())
        val error = model.requests[1].messages.last() as ChatMessage.Tool
        assertTrue(error.content, error.content.startsWith("Error: Element [99] is not on the current screen"))
    }

    @Test
    fun aValidCallResetsTheInvalidStreak() = runTest {
        val model = ScriptedModel.of(
            toolCall("swipe", "{}"),
            toolCall("tap", "not json"),
            toolCall("read_screen"),
            toolCall("open_app", "{}"),
            toolCall("finish", """{"reply":"Done."}"""),
        )
        val result = agentLoop(model, FakeScreen(settingsMain)).run("do it", RecordingListener())

        assertEquals(AgentStatus.Completed, result.status)
        assertEquals(5, result.steps)
    }

    @Test
    fun withoutScreenControlOnlyLaunchingAndReplyingAreOffered() = runTest {
        val apps = FakeApps(null, mapOf("Chrome" to launcher))
        val model = ScriptedModel.of(
            toolCall("open_app", """{"name":"Chrome"}"""),
            toolCall("finish", """{"reply":"I opened Chrome. Turn on Wakey screen control to search for you."}"""),
        )

        val result = agentLoop(model, null, apps).run("open Chrome and search for cats", RecordingListener())

        assertEquals(AgentStatus.Completed, result.status)
        assertEquals(listOf("open_app", "finish", "ask_user"), model.requests[0].tools.map { it.name })
        assertTrue((model.requests[0].messages[0] as ChatMessage.System).text.contains("Screen control is off"))
        assertEquals("Request: open Chrome and search for cats\nReply language: English", (model.requests[0].messages.last() as ChatMessage.User).text)
        assertTrue((model.requests[1].messages.last() as ChatMessage.Tool).content.contains("can't be checked"))
        assertEquals(1_000L, result.firstActionAtMs)
    }

    @Test
    fun lockedPhoneOnlyAllowsReplies() = runTest {
        val screen = FakeScreen(settingsMain).apply { locked = true }
        val model = ScriptedModel.of(toolCall("finish", """{"reply":"Please unlock your phone first."}"""))

        val result = agentLoop(model, screen).run("open WhatsApp and message mom", RecordingListener())

        assertEquals(AgentStatus.Completed, result.status)
        assertEquals(listOf("finish", "ask_user"), model.requests[0].tools.map { it.name })
        assertTrue((model.requests[0].messages[0] as ChatMessage.System).text.contains("The phone is locked"))
        assertTrue(screen.statuses.isEmpty())
    }

    @Test
    fun plainTextAnswerFinishesAndAskUserNeedsUser() = runTest {
        val answered = agentLoop(ScriptedModel.of(textReply("It's 5 pm.")), FakeScreen(launcher)).run("what time is it", RecordingListener())
        assertEquals(AgentStatus.Completed, answered.status)
        assertEquals("It's 5 pm.", answered.reply)

        val asked = agentLoop(ScriptedModel.of(toolCall("ask_user", """{"question":"Which Priya?"}""")), FakeScreen(chat))
            .run("message Priya", RecordingListener())
        assertEquals(AgentStatus.NeedsUser, asked.status)
        assertEquals("Which Priya?", asked.reply)
    }

    @Test
    fun screenshotIsSentAsAnImageAndDroppedAfterTheNextAction() = runTest {
        val screen = FakeScreen(settingsMain).apply {
            screenshotResult = ScreenshotResult("QUJD", 720, 1280)
            transitions["Bluetooth"] = bluetooth
        }
        val model = ScriptedModel.of(
            toolCall("take_screenshot"),
            toolCall("tap", """{"label":"bluetooth"}"""),
            toolCall("finish", """{"reply":"Done."}"""),
        )
        val listener = RecordingListener()

        val result = agentLoop(model, screen).run("open bluetooth", listener)

        assertEquals(AgentStatus.Completed, result.status)
        val withImage = model.requests[1].messages.last() as ChatMessage.User
        assertEquals("QUJD", withImage.imageJpegBase64)
        assertTrue(model.requests[2].messages.none { it is ChatMessage.User && it.imageJpegBase64 != null })
        assertEquals(listOf("screenshot", "tap Bluetooth"), screen.log)
        assertEquals("take_screenshot", listener.actions.first().toolName)
    }

    @Test
    fun timeoutStopsTheRun() = runTest {
        val model = ScriptedModel { _, _ ->
            delay(200_000)
            textReply("too late")
        }
        val screen = FakeScreen(settingsMain)

        val result = agentLoop(model, screen, timeoutMs = 120_000).run("open bluetooth", RecordingListener())

        assertEquals(AgentStatus.Timeout, result.status)
        assertEquals(1, screen.statusHidden)
    }

    @Test
    fun modelErrorsBecomeShortReplies() = runTest {
        val model = ScriptedModel { _, _ -> throw LlmException(LlmException.Kind.Network, "offline") }
        val result = agentLoop(model, FakeScreen(settingsMain)).run("open bluetooth", RecordingListener())
        assertEquals(AgentStatus.Failed, result.status)
        assertTrue(result.reply.contains("internet"))
        assertEquals(0, result.llmCalls)
    }

    @Test
    fun devanagariGoalsGetHindiCannedReplies() = runTest {
        val model = ScriptedModel { _, _ -> toolCall("tap", """{"element_id":4}""") }
        val result = agentLoop(model, FakeScreen(settingsMain)).run("ऐप्स खोलो और कुछ करो", RecordingListener())
        assertEquals("स्क्रीन बदल नहीं रही थी, इसलिए मैं रुक गया। यह कदम आपको खुद करना पड़ सकता है।", result.reply)
    }

    @Test
    fun historyAndAppLabelsGoIntoThePrompt() = runTest {
        val model = ScriptedModel.of(textReply("Calling."))
        val apps = FakeApps(null, mapOf("WhatsApp" to chat))
        val listener = RecordingListener(history = listOf("user" to "call mom", "assistant" to "Which number?"))

        agentLoop(model, FakeScreen(launcher), apps).run("the mobile one", listener)

        val messages = model.requests[0].messages
        assertTrue((messages[0] as ChatMessage.System).text.contains("Installed apps: WhatsApp"))
        assertEquals(ChatMessage.User("call mom"), messages[1])
        assertEquals(ChatMessage.Assistant("Which number?"), messages[2])
    }

    @Test
    fun statusPillStopButtonCallsTheStopCallback() = runTest {
        var stopped = false
        val screen = FakeScreen(settingsMain)
        agentLoop(ScriptedModel.of(textReply("Hi.")), screen, onStop = { stopped = true }).run("hello", RecordingListener())
        screen.lastStop?.invoke()
        assertTrue(stopped)
        assertFalse(screen.statuses.isEmpty())
    }

    @Test
    fun onlyTheFirstOfSeveralToolCallsRuns() = runTest {
        val screen = FakeScreen(settingsMain).apply { transitions["Bluetooth"] = bluetooth }
        val two = toolCall("tap", """{"element_id":3}""").let {
            it.copy(toolCalls = it.toolCalls + ToolCall("second", "go_home", "{}"))
        }
        val model = ScriptedModel.of(two, toolCall("finish", """{"reply":"Done."}"""))

        agentLoop(model, screen).run("open bluetooth", RecordingListener())

        assertEquals(listOf("tap Bluetooth"), screen.log)
        val assistant = model.requests[1].messages.filterIsInstance<ChatMessage.Assistant>().last()
        assertEquals(1, assistant.toolCalls.size)
    }

    private fun ChatMessage.text(): String = when (this) {
        is ChatMessage.System -> text
        is ChatMessage.User -> text
        is ChatMessage.Assistant -> text.orEmpty()
        is ChatMessage.Tool -> content
    }
}
