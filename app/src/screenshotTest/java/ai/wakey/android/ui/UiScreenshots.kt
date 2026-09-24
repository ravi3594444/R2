package ai.wakey.android.ui

import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AgentActionInfo
import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.core.ChatEntry
import ai.wakey.android.core.InputSource
import ai.wakey.android.core.Speaker
import ai.wakey.android.core.TurnTimings
import ai.wakey.android.ui.components.NoteText
import ai.wakey.android.ui.components.RadioRow
import ai.wakey.android.ui.components.SectionCard
import ai.wakey.android.ui.components.StatusBadge
import ai.wakey.android.ui.components.SwitchRow
import ai.wakey.android.ui.theme.WakeyTheme
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Renders the main screens from sample state to PNGs for a visual check. Not a regression test:
 * it asserts nothing. Runs only with `-Pwakey.screenshots` (see app/build.gradle.kts).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi", application = Application::class)
class UiScreenshots {
    private val settings = WakeySettings(onboardingDone = true)
    private val ready = SetupStatus(microphone = true, notifications = true, screenControl = true, batteryUnrestricted = true, defaultAssistant = true)

    @Test
    fun mainIdle() = dashboard("main_1_idle", AssistantUiState())

    @Test
    fun mainListening() = dashboard("main_2_wake_listening", AssistantUiState(phase = AssistantPhase.WakeListening, wakeServiceRunning = true))

    @Test
    fun mainHearing() = dashboard(
        "main_3_hearing",
        AssistantUiState(phase = AssistantPhase.Hearing, wakeServiceRunning = true, liveTranscript = "open YouTube and search for lo-fi music"),
        level = 0.7f,
    )

    @Test
    fun mainActing() {
        val action = AgentActionInfo(2, 12, "Tapping “Bluetooth”", "tap")
        dashboard(
            "main_4_acting",
            AssistantUiState(
                phase = AssistantPhase.Acting,
                wakeServiceRunning = true,
                entries = conversation(),
                currentAction = action,
                recentActions = listOf(AgentActionInfo(1, 12, "Opening Settings", "open_app", "Opened Settings", true), action),
            ),
        )
    }

    @Test
    fun mainProblem() = dashboard(
        "main_5_problem",
        AssistantUiState(
            phase = AssistantPhase.WakeListening,
            wakeServiceRunning = true,
            entries = conversation() + ChatEntry(9, Speaker.Wakey, "I couldn't find an app called “Instagrm”.", 0, isError = true),
            statusMessage = "Android is muting Wakey's microphone. Open Wakey once so it can listen in the background.",
            statusIsError = true,
        ),
    )

    @Test
    fun mainSetupNeeded() = dashboard("main_6_setup_needed", AssistantUiState(), setup = ready.copy(screenControl = false))

    @Test
    fun settingsControls() = shot("settings_controls") {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard("Wake word", icon = Icons.Rounded.Hearing, subtitle = "Short, distinct English phrases work best.") {
                OutlinedTextField("Hey Wakey", {}, label = { Text("Wake phrase") }, singleLine = true)
                Slider(0.5f, {})
                SwitchRow("Wake sound", checked = true, onCheckedChange = {}, subtitle = "A short chime when Wakey hears you.")
                SwitchRow("Speak replies", checked = false, onCheckedChange = {})
                Button({}) { Text("Apply") }
            }
            SectionCard("Voice", icon = Icons.Rounded.RecordVoiceOver) {
                RadioRow("Meena (Indian English)", selected = true, onClick = {}, subtitle = "Deepgram")
                RadioRow("Android voice", selected = false, onClick = {})
                StatusBadge(ok = true, text = "Saved ••••ab12")
                StatusBadge(ok = false, text = "Not set")
                OutlinedButton({}) { Text("Preview") }
                NoteText("Detection runs on this phone.")
            }
        }
    }

    private fun conversation() = listOf(
        ChatEntry(1, Speaker.User, "Turn on the flashlight", 0, InputSource.WakeWord),
        ChatEntry(
            2, Speaker.Wakey, "Flashlight is on.", 0,
            timings = TurnTimings(InputSource.WakeWord, wakeDetectionMs = 320, transcriptionMs = 610, firstActionMs = 40, totalMs = 900, route = "fast"),
        ),
        ChatEntry(3, Speaker.User, "Open Settings and find Bluetooth", 0, InputSource.WakeWord),
    )

    private fun dashboard(name: String, state: AssistantUiState, level: Float = 0f, setup: SetupStatus = ready) = shot(name) {
        WakeyDashboard(state, { level }, settings, setup, "Meena", noActions)
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            WakeyTheme { Surface(color = MaterialTheme.colorScheme.background) { content() } }
        }
        // Let enter animations settle; the orb's own loops keep running.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File(System.getProperty("wakey.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val noActions = DashboardActions(
        talk = {}, stop = {}, submitText = {}, dismissStatus = {}, setWakeListening = {}, setEngine = {},
        openVoicePicker = {}, preview = {}, stopPreview = {}, allowMicrophone = {}, enableScreenControl = {},
        openSettings = {}, openSetup = {},
    )
}
