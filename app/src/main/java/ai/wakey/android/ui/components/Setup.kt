package ai.wakey.android.ui.components

import ai.wakey.android.ui.SetupStatus
import ai.wakey.android.ui.WakeySetup
import ai.wakey.android.ui.notificationsNeedRuntimeGrant
import ai.wakey.android.ui.theme.WakeyColors
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

const val PRIVACY_LINE = "Wake word runs on this phone. Audio goes to Deepgram only after you say the wake word or tap the mic."

/** One permission or system setting: what it is for, whether it is done, and how to do it. */
@Composable
private fun SetupItem(
    icon: ImageVector,
    title: String,
    done: Boolean,
    doneLabel: String,
    todoLabel: String,
    description: String,
    modifier: Modifier = Modifier,
    details: @Composable ColumnScope.() -> Unit = {},
    actions: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            StatusBadge(ok = done, text = if (done) doneLabel else todoLabel)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            details()
            if (!done) actions()
        }
    }
}

@Composable
fun MicrophoneSetupItem(setup: WakeySetup, modifier: Modifier = Modifier) {
    SetupItem(
        icon = Icons.Rounded.Mic,
        title = "Microphone",
        done = setup.status.microphone,
        doneLabel = "Allowed",
        todoLabel = "Not allowed",
        description = "Needed to hear the wake word and your requests. $PRIVACY_LINE",
        modifier = modifier,
    ) {
        FilledTonalButton(onClick = setup::requestVoicePermissions) { Text("Allow microphone") }
    }
}

/** Only Android 13+ asks for notifications at runtime; earlier versions show the row only if they were switched off. */
@Composable
fun NotificationsSetupItem(setup: WakeySetup, modifier: Modifier = Modifier) {
    if (!notificationsNeedRuntimeGrant && setup.status.notifications) return
    SetupItem(
        icon = Icons.Rounded.Notifications,
        title = "Notifications",
        done = setup.status.notifications,
        doneLabel = "Allowed",
        todoLabel = "Off",
        description = "Shows the listening notification with a Stop button, and approval prompts while Wakey works in other apps.",
        modifier = modifier,
    ) {
        FilledTonalButton(onClick = setup::requestNotifications) { Text("Allow notifications") }
    }
}

@Composable
fun ScreenControlSetupItem(setup: WakeySetup, modifier: Modifier = Modifier) {
    SetupItem(
        icon = Icons.Rounded.Accessibility,
        title = "Wakey screen control",
        done = setup.status.screenControl,
        doneLabel = "On",
        todoLabel = "Off",
        description = "Lets Wakey read the controls on your screen and tap, type, scroll or go back — only for requests you make. " +
            "It asks before sending, buying or changing account settings.",
        modifier = modifier,
        details = {
            if (!setup.status.screenControl) {
                NoteText("Android shows a strong warning when you turn this on. That is normal for any app that can control the screen.")
                Text(
                    "Settings → Accessibility → Installed apps (or Downloaded apps) → Wakey screen control → On",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    NoteText("If the switch is greyed out: App info → ⋮ → Allow restricted settings, then try again.")
                }
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = setup::openAccessibilitySettings) { Text("Open Accessibility") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                TextButton(onClick = setup::openAppSettings) { Text("App info") }
            }
        }
    }
}

@Composable
fun BatterySetupItem(setup: WakeySetup, modifier: Modifier = Modifier) {
    SetupItem(
        icon = Icons.Rounded.BatteryChargingFull,
        title = "Battery optimisation",
        done = setup.status.batteryUnrestricted,
        doneLabel = "Unrestricted",
        todoLabel = "Optimised",
        description = "Android may pause the wake word while the screen is off. Exempt Wakey to keep it reliable.",
        modifier = modifier,
        details = {
            if (!setup.status.batteryUnrestricted) {
                NoteText("In the list choose All apps → Wakey → Don't optimise. Some phones call it App battery usage → Unrestricted.")
            }
        },
    ) {
        FilledTonalButton(onClick = setup::openBatterySettings) { Text("Open battery settings") }
    }
}

@Composable
fun LockedPhoneNote(modifier: Modifier = Modifier) {
    NoteText(
        "Wakey can't unlock a phone protected by a PIN, pattern, password or biometrics. Unlock it first for tasks in other apps.",
        modifier = modifier,
        icon = Icons.Rounded.Lock,
    )
}

/** Every permission and system setting Wakey uses, in the order they matter. */
@Composable
fun SetupChecklist(setup: WakeySetup, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MicrophoneSetupItem(setup)
        NotificationsSetupItem(setup)
        ScreenControlSetupItem(setup)
        BatterySetupItem(setup)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LockedPhoneNote()
    }
}

/** A compact reminder on the main screen while voice or screen control is not set up. */
@Composable
fun SetupNeededCard(
    status: SetupStatus,
    onAllowMicrophone: () -> Unit,
    onEnableScreenControl: () -> Unit,
    onOpenSetup: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = WakeyColors.Amber.copy(alpha = 0.1f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = WakeyColors.Amber, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Setup needed", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onHide) { Icon(Icons.Rounded.Close, contentDescription = "Hide setup reminder") }
            }
            if (!status.microphone) {
                SetupNeededLine("Microphone", "Needed for voice. Typing works without it.", "Allow", onAllowMicrophone)
            }
            if (!status.screenControl) {
                SetupNeededLine("Wakey screen control", "Lets Wakey tap and type in other apps.", "Turn on", onEnableScreenControl)
            }
            TextButton(onClick = onOpenSetup) { Text("All setup steps") }
        }
    }
}

@Composable
private fun SetupNeededLine(title: String, description: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}
