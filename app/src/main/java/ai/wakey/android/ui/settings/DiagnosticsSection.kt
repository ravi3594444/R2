package ai.wakey.android.ui.settings

import ai.wakey.android.BuildConfig
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.ui.components.NoteText
import ai.wakey.android.ui.components.SectionCard
import ai.wakey.android.ui.timingDetails
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun DiagnosticsSection(state: AssistantUiState, onClearConversation: () -> Unit) {
    SectionCard("Diagnostics", icon = Icons.Rounded.Speed, subtitle = "Timings for the last request, measured on this phone.") {
        val timings = state.lastTimings
        if (timings == null) {
            NoteText("Make a request and its timings will appear here.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                timingDetails(timings).forEach { (label, value) ->
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
        OutlinedButton(onClick = onClearConversation, enabled = state.entries.isNotEmpty()) {
            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Clear conversation")
        }
        NoteText("Wakey ${BuildConfig.VERSION_NAME}")
    }
}
