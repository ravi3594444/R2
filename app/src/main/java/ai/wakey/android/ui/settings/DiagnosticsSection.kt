package ai.wakey.android.ui.settings

import ai.wakey.android.BuildConfig
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AssistantController
import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.ui.components.NoteText
import ai.wakey.android.ui.components.SectionCard
import ai.wakey.android.ui.diagnosticsReport
import ai.wakey.android.ui.timingDetails
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DiagnosticsSection(controller: AssistantController, state: AssistantUiState, settings: WakeySettings) {
    val stats by controller.wakeStats.collectAsStateWithLifecycle()
    val micMuted by controller.micMuted.collectAsStateWithLifecycle()
    SectionCard("Diagnostics", icon = Icons.Rounded.Speed, subtitle = "Timings for the last request, measured on this phone.") {
        val timings = state.lastTimings
        if (timings == null) {
            NoteText("Make a request and its timings will appear here.")
        } else {
            DetailRows(timingDetails(timings))
        }
        DetailRows(
            listOf(
                "Wake phrase heard" to "${stats.wakes + stats.checksConfirmed}",
                "Unsure, then confirmed" to "${stats.checksConfirmed}",
                "Unsure, then dropped" to "${stats.checksRejected}",
                "Microphone" to when {
                    !state.wakeServiceRunning -> "Not listening"
                    micMuted -> "Muted by Android"
                    state.wakeWordEnabled || state.phase == AssistantPhase.Hearing -> "Listening"
                    else -> "Ready for the floating button"
                },
            ),
        )
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }
        OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(diagnosticsReport(context, settings, state, stats, micMuted)))
            copied = true
        }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (copied) "Report copied" else "Copy report")
        }
        NoteText("If the wake word doesn't respond, copy the report and paste it in your message. It contains no keys or audio.")
        OutlinedButton(onClick = controller::clearConversation, enabled = state.entries.isNotEmpty()) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Clear conversation")
        }
        NoteText("Wakey ${BuildConfig.VERSION_NAME}")
    }
}

@Composable
private fun DetailRows(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
            }
        }
    }
}
