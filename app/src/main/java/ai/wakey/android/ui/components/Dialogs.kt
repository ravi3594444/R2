package ai.wakey.android.ui.components

import ai.wakey.android.core.PendingConfirmation
import ai.wakey.android.ui.PermissionNotice
import ai.wakey.android.ui.WakeySetup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Approval for a consequential action. Back counts as Deny; an outside tap does nothing, so a
 * stray touch can neither approve nor silently cancel.
 */
@Composable
fun ConfirmationDialog(
    confirmation: PendingConfirmation,
    voiceAnswerPossible: Boolean,
    onAnswer: (id: Long, approved: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAnswer(confirmation.id, false) },
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Rounded.GppMaybe, contentDescription = null) },
        title = { Text(confirmation.question) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (confirmation.detail.isNotBlank()) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(
                            confirmation.detail,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text(
                    if (voiceAnswerPossible) "Wakey is waiting for you. You can also say “yes” or “no”."
                    else "Wakey is waiting for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onAnswer(confirmation.id, true) }) { Text("Approve") } },
        dismissButton = { OutlinedButton(onClick = { onAnswer(confirmation.id, false) }) { Text("Deny") } },
    )
}

/** Explains a declined permission, with the only way back once Android stops asking: app settings. */
@Composable
fun PermissionNoticeDialog(setup: WakeySetup) {
    val notice = setup.notice ?: return
    val microphone = notice == PermissionNotice.MicrophoneDenied
    AlertDialog(
        onDismissRequest = setup::dismissNotice,
        icon = { Icon(if (microphone) Icons.Rounded.MicOff else Icons.Rounded.NotificationsOff, contentDescription = null) },
        title = { Text(if (microphone) "Microphone is off" else "Notifications are off") },
        text = {
            Text(
                if (microphone) {
                    "Wakey needs the microphone to hear the wake word and your requests. Typing still works.\n\n" +
                        "To allow it: App settings → Permissions → Microphone → Allow."
                } else {
                    "Wakey still works, but Android hides the listening notification with its Stop button, " +
                        "and approval prompts while Wakey works in other apps.\n\n" +
                        "To allow them: App settings → Notifications."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                setup.dismissNotice()
                setup.openAppSettings()
            }) { Text("Open app settings") }
        },
        dismissButton = { TextButton(onClick = setup::dismissNotice) { Text("Not now") } },
    )
}
