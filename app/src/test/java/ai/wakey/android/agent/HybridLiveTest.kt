package ai.wakey.android.agent

import ai.wakey.android.config.WakeySettings
import ai.wakey.android.llm.DecisionModel
import ai.wakey.android.llm.JevDecisionModel
import ai.wakey.android.llm.OpenAiCompatibleChatModel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Real Fireworks + real Jev (AI/ML API) on scripted screens, LLM-only vs hybrid. Skipped unless
 * FIREWORKS_API_KEY and AIMLAPI_API_KEY are set; prints timings for the report.
 */
class HybridLiveTest {
    private val fireworksKey = System.getenv("FIREWORKS_API_KEY")?.trim().orEmpty()
    private val aimlKey = System.getenv("AIMLAPI_API_KEY")?.trim().orEmpty()
    private val http = OkHttpClient()
    private val llm = OpenAiCompatibleChatModel(
        http, { WakeySettings.DEFAULT_LLM_BASE_URL }, { WakeySettings.DEFAULT_LLM_MODEL }, { fireworksKey },
    )
    private val jev = JevDecisionModel(
        http, { WakeySettings.DEFAULT_DECISION_BASE_URL }, { WakeySettings.DEFAULT_DECISION_MODEL }, { aimlKey },
    )

    @Before
    fun keys() = assumeTrue(fireworksKey.isNotEmpty() && aimlKey.isNotEmpty())

    private val settingsMain = FakeUi(
        "com.android.settings", "Settings",
        listOf(
            "Search settings", "Network & internet – Mobile, Wi-Fi, hotspot", "Connected devices – Bluetooth, pairing",
            "Apps – Assistant, recent apps, default apps", "Notifications", "Battery – 72%", "Display", "Sound & vibration",
        ),
    )
    private val connected = FakeUi(
        "com.android.settings", "Settings",
        listOf("Connected devices", "Pair new device", "Bluetooth – Off", "Saved devices", "Connection preferences – Bluetooth, NFC"),
        texts = setOf("Connected devices"),
    )
    private val bluetooth = FakeUi(
        "com.android.settings", "Settings",
        listOf("Bluetooth", "Use Bluetooth (off)", "Pair new device", "Device name – Pixel 8"), texts = setOf("Bluetooth"),
    )
    private val instagramHome = FakeUi(
        "com.instagram.android", "Instagram",
        listOf("Instagram", "Notifications", "Messages", "Your story", "priya.sharma liked a post", "Home", "Search and explore", "Reels", "Profile"),
        texts = setOf("Instagram", "priya.sharma liked a post"),
    )
    private val instagramResults = FakeUi(
        "com.instagram.android", "Instagram",
        listOf("Search", "Accounts", "cats_of_instagram", "catsofworld", "#cats", "Reels", "Cats being funny"),
        texts = setOf("Accounts"),
    )

    private fun screens(): Pair<FakeScreen, FakeApps> {
        val screen = FakeScreen(settingsMain).apply {
            transitions["Connected devices – Bluetooth, pairing"] = connected
            transitions["Bluetooth – Off"] = bluetooth
            transitions["Search and explore"] = instagramResults.copy(items = listOf("Search", "Recent", "Explore grid"), texts = setOf("Recent"))
            onText = { ui = instagramResults }
        }
        return screen to FakeApps(screen, mapOf("Settings" to settingsMain, "Instagram" to instagramHome))
    }

    /** Prints every Jev decision so misses can be diagnosed. */
    private val loggedJev = object : DecisionModel {
        override suspend fun choose(state: String, instructions: String, options: Map<String, String>) =
            jev.choose(state, instructions, options).also {
                val top = it.probabilities.entries.sortedByDescending { e -> e.value }.take(3).joinToString { e -> "${e.key}=${"%.2f".format(e.value)}" }
                println("  JEV ${it.latencyMs} ms -> ${it.choice} (conf ${"%.2f".format(it.confidence)}; $top) of ${options.keys}")
            }

        override suspend fun testConnection() = jev.testConnection()
    }

    private fun run(goal: String, decisions: DecisionModel?): AgentResult = runBlocking {
        val (screen, apps) = screens()
        val loop = AgentLoop(llm, apps, { screen }, { WakeySettings() }, System::currentTimeMillis, 60_000L, { decisions })
        val started = System.nanoTime()
        val result = loop.run(goal, RecordingListener())
        val ms = (System.nanoTime() - started) / 1_000_000
        println(
            "LIVE ${if (decisions == null) "llm-only" else "hybrid  "} \"$goal\": ${result.status} in $ms ms, " +
                "${result.llmCalls} LLM calls, ${result.decisionCalls} Jev decisions, actions=${screen.log} reply=${result.reply}",
        )
        result
    }

    @Test
    fun settingsBluetooth() {
        val goal = "open Settings and find Bluetooth"
        assertEquals(AgentStatus.Completed, run(goal, null).status)
        assertEquals(AgentStatus.Completed, run(goal, loggedJev).status)
    }

    @Test
    fun instagramSearch() {
        val goal = "open Instagram and search for cats"
        assertEquals(AgentStatus.Completed, run(goal, null).status)
        assertEquals(AgentStatus.Completed, run(goal, loggedJev).status)
    }
}
