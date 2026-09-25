package ai.wakey.android.ui.settings

import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AssistantController
import ai.wakey.android.ui.WakeySetup
import ai.wakey.android.ui.components.NoteText
import ai.wakey.android.ui.components.SectionCard
import ai.wakey.android.ui.components.StatusBadge
import ai.wakey.android.ui.components.SwitchRow
import ai.wakey.android.ui.floatingButtonNote
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** The floating button switch and how tasks, reminders and alarms work. */
@Composable
fun FloatingButtonSection(controller: AssistantController, settings: WakeySettings, setup: WakeySetup) {
    val context = LocalContext.current
    SectionCard(
        "Floating button & tasks",
        icon = Icons.Rounded.TouchApp,
        subtitle = "A small Wakey button over your other apps, and work Wakey does later.",
    ) {
        SwitchRow(
            title = "Show the floating button",
            subtitle = floatingButtonNote(settings.floatingButton, setup.status.screenControl, setup.status.screenControlRunning),
            checked = settings.floatingButton,
            onCheckedChange = { enabled ->
                if (enabled) {
                    setup.withMicrophone(forWakeWord = true) {
                        controller.setFloatingButton(context, true)
                        if (!setup.status.screenControl) setup.openAccessibilitySettings()
                    }
                } else {
                    controller.setFloatingButton(context, false)
                }
            },
        )
        NoteText("Tap: talk, no wake word needed. Hold: see what's running, next and scheduled. Drag: move it; it snaps to the nearest edge.")
        NoteText(
            "While it's on, Wakey keeps its microphone service ready (you'll see its notification) so a tap can listen from any app. " +
                "Nothing is sent anywhere until you tap it or say the wake word.",
            icon = Icons.Rounded.Lock,
        )
        if (settings.floatingButton && !setup.status.screenControl) {
            FilledTonalButton(onClick = setup::openAccessibilitySettings) { Text("Turn on screen control") }
        }
        NoteText(
            "Schedule by voice or text: “call mum at 4”, “ring me at 5”, “wake me up at 6:30”, “remind me to drink water in " +
                "10 minutes”, “play this song after this”. Say “show my tasks” or “cancel my alarm” to manage them.",
            icon = Icons.Rounded.Alarm,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(ok = setup.status.exactAlarms, text = if (setup.status.exactAlarms) "Exact alarms allowed" else "Exact alarms off")
            if (!setup.status.exactAlarms) TextButton(onClick = setup::openExactAlarmSettings) { Text("Allow") }
        }
    }
}
