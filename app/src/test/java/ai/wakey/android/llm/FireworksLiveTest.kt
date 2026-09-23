package ai.wakey.android.llm

import ai.wakey.android.agent.AgentPrompt
import ai.wakey.android.agent.AgentStatus
import ai.wakey.android.agent.FakeApps
import ai.wakey.android.agent.FakeScreen
import ai.wakey.android.agent.FakeUi
import ai.wakey.android.agent.RecordingListener
import ai.wakey.android.agent.agentLoop
import ai.wakey.android.config.WakeySettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Live checks against the configured Fireworks model: 5–7 short requests in total. Skipped unless
 * FIREWORKS_API_KEY is set. Prints latency and token counts per call; never prints the key.
 */
class FireworksLiveTest {
    private val key = System.getenv("FIREWORKS_API_KEY")?.trim().orEmpty()
    private val model = OpenAiCompatibleChatModel(
        OkHttpClient(),
        baseUrl = { WakeySettings.DEFAULT_LLM_BASE_URL },
        model = { WakeySettings.DEFAULT_LLM_MODEL },
        apiKey = { key },
    )

    /** Logs each call's numbers and the tool calls the model chose. */
    private val logged = object : ChatModel {
        var calls = 0
        override suspend fun complete(request: ChatRequest): ChatResponse = model.complete(request).also { r ->
            calls++
            val tools = r.toolCalls.joinToString { "${it.name}(${it.argumentsJson})" }
            println("LIVE call $calls: ${r.latencyMs} ms, ${r.promptTokens} prompt + ${r.completionTokens} completion tokens, finish=${r.finishReason}, tools=[$tools], text=${r.text?.take(120)}")
        }

        override suspend fun testConnection() = model.testConnection()
    }

    @Before
    fun requireKey() = assumeTrue("FIREWORKS_API_KEY not set", key.isNotEmpty())

    @Test
    fun connectionTest() = runBlocking {
        val summary = model.testConnection()
        println("LIVE testConnection: $summary")
        assertTrue(summary, summary.startsWith("OK · ${WakeySettings.DEFAULT_LLM_MODEL.substringAfterLast('/')} replied in "))
    }

    /** Pixel-style Settings main list; summaries are plain text under their clickable rows. */
    private val settingsMain = FakeUi(
        "com.android.settings", "Settings",
        listOf(
            "Settings", "Search settings", "Network & internet", "Mobile, Wi‑Fi, hotspot", "Connected devices",
            "Bluetooth, pairing", "Apps", "Assistant, recent apps, default apps", "Notifications",
            "Notification history, conversations", "Battery", "100%", "Storage", "34% used - 84.12 GB free",
            "Sound & vibration", "Volume, haptics, Do Not Disturb", "Display", "Dark theme, font size, brightness",
            "Wallpaper & style", "Accessibility", "Security & privacy", "Location", "Passwords & accounts",
            "Digital Wellbeing & parental controls", "Google", "System", "About phone",
        ),
        texts = setOf(
            "Settings", "Mobile, Wi‑Fi, hotspot", "Bluetooth, pairing", "Assistant, recent apps, default apps",
            "Notification history, conversations", "100%", "34% used - 84.12 GB free",
            "Volume, haptics, Do Not Disturb", "Dark theme, font size, brightness",
        ),
    )
    private val connectedDevices = FakeUi(
        "com.android.settings", "Settings",
        listOf("Connected devices", "Pair new device", "Bluetooth", "Saved devices", "See all", "Connection preferences", "NFC, Nearby Share, Android Auto"),
        texts = setOf("Connected devices", "NFC, Nearby Share, Android Auto"),
    )
    private val bluetoothPage = FakeUi(
        "com.android.settings", "Settings",
        listOf("Bluetooth", "Use Bluetooth", "Pair new device", "Device name", "Pixel 8"),
        texts = setOf("Bluetooth"),
    )

    private fun settingsScreen(start: FakeUi) = FakeScreen(start).apply {
        transitions["Connected devices"] = connectedDevices
        transitions["Bluetooth, pairing"] = connectedDevices
        transitions["Bluetooth"] = bluetoothPage
    }

    /** Full flow, 2–4 requests: Settings list → Connected devices → Bluetooth page → English reply. */
    @Test
    fun settingsAgentTapsAValidElementAndReachesBluetooth() = runBlocking {
        val screen = settingsScreen(settingsMain)

        val result = agentLoop(logged, screen, FakeApps(screen, mapOf("Settings" to settingsMain)), maxSteps = 4)
            .run("open Settings and find Bluetooth", RecordingListener())

        println("LIVE agent: status=${result.status} steps=${result.steps} calls=${result.llmCalls} tokens=${result.promptTokens}+${result.completionTokens} actions=${screen.log} reply=${result.reply}")
        val firstAction = screen.log.firstOrNull().orEmpty()
        assertTrue("first action: $firstAction", firstAction.removePrefix("tap ") in settingsMain.items)
        assertEquals(AgentStatus.Completed, result.status)
        assertEquals(bluetoothPage, screen.ui)
        assertEquals(result.reply, AgentPrompt.ReplyLanguage.English, AgentPrompt.ReplyLanguage.of(result.reply))
    }

    /** One request: seeing "Bluetooth" in a list is not "finding" it; the agent must open it. */
    @Test
    fun opensBluetoothInsteadOfStoppingAtTheList() = runBlocking {
        val screen = settingsScreen(connectedDevices)

        val result = agentLoop(logged, screen, maxSteps = 1).run("open Settings and find Bluetooth", RecordingListener())

        println("LIVE one step: status=${result.status} actions=${screen.log} reply=${result.reply}")
        assertEquals(listOf("tap Bluetooth"), screen.log)
    }

    @Test
    fun imageInputIsUnderstood() = runBlocking {
        val response = logged.complete(
            ChatRequest(
                listOf(ChatMessage.User("What colour is this image? Answer with one word.", imageJpegBase64 = solidJpeg(0xD01010))),
                maxTokens = 16,
            ),
        )
        assertTrue(response.text, response.text.orEmpty().contains("red", ignoreCase = true))
    }

    /**
     * A 96×96 single-colour JPEG. Reflection because unit tests compile against android.jar, which
     * has no java.awt / javax.imageio, although the test JVM does.
     */
    private fun solidJpeg(rgb: Int): String {
        val size = 96
        val imageClass = Class.forName("java.awt.image.BufferedImage")
        val image = imageClass.getConstructor(Int::class.java, Int::class.java, Int::class.java).newInstance(size, size, 1)
        val setRgb = imageClass.getMethod("setRGB", Int::class.java, Int::class.java, Int::class.java)
        for (x in 0 until size) for (y in 0 until size) setRgb.invoke(image, x, y, rgb)
        val out = ByteArrayOutputStream()
        Class.forName("javax.imageio.ImageIO")
            .getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, java.io.OutputStream::class.java)
            .invoke(null, image, "jpg", out)
        println("LIVE image: ${out.size()} byte JPEG")
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }
}
