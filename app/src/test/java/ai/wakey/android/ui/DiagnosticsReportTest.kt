package ai.wakey.android.ui

import ai.wakey.android.agent.Decider
import ai.wakey.android.agent.StepTiming
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.core.InputSource
import ai.wakey.android.core.TurnTimings
import ai.wakey.android.core.WakeStats
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    private val device = DeviceFacts("Xiaomi", "Redmi Note 12", "14", 34, "0.2.2")
    private val setup = SetupStatus(microphone = true, notifications = true, screenControl = false, batteryUnrestricted = false, defaultAssistant = true)

    @Test
    fun `report names the phone, the settings that matter and wake activity`() {
        val report = diagnosticsReport(
            device, setup, WakeySettings(wakeListeningWanted = true),
            AssistantUiState(wakeServiceRunning = true, micLevel = 0.42f),
            WakeStats(wakes = 3, checksConfirmed = 2, checksRejected = 5, lastRejectedHeard = "hey what", mutedEvents = 1),
            micMuted = false,
        )
        for (expected in listOf(
            "Xiaomi Redmi Note 12, Android 14 (API 34)", "Digital assistant app is Wakey: yes", "battery unrestricted): NO",
            "Listen for wake word switch: on; service running: yes", "open, level 0.42", "3 wakes, 2 confirmed checks, 5 dropped checks, mic muted 1 times",
            "Last dropped check heard: “hey what”",
        )) {
            assertTrue("missing “$expected” in\n$report", expected in report)
        }
    }

    @Test
    fun `the last agent run is listed step by step`() {
        val timeline = listOf(
            StepTiming(Decider.Direct, 0, 900, "Opening Instagram", true),
            StepTiming(Decider.Jev, 450, 700, "Tapping “Search”", true),
            StepTiming(Decider.Llm, 1_600, 1_100, "Typing “cats”", false),
        )
        val state = AssistantUiState(lastTimings = TurnTimings(InputSource.WakeWord, timeline = timeline))
        val report = diagnosticsReport(device, setup, WakeySettings(), state, WakeStats(), micMuted = false)
        assertTrue(report, "  1. direct 0 ms → Opening Instagram 900 ms" in report)
        assertTrue(report, "  2. Jev 450 ms → Tapping “Search” 700 ms" in report)
        assertTrue(report, "  3. LLM 1.6 s → Typing “cats” 1.1 s FAILED" in report)
    }

    @Test
    fun `a muted microphone is called out`() {
        val report = diagnosticsReport(device, setup, WakeySettings(), AssistantUiState(wakeServiceRunning = true), WakeStats(), micMuted = true)
        assertTrue(report, "MUTED by Android" in report)
    }
}
