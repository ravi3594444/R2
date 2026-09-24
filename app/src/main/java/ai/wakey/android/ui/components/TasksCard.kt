package ai.wakey.android.ui.components

import ai.wakey.android.tasks.TaskBoard
import ai.wakey.android.ui.theme.WakeyColors
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The dashboard's task list: what runs next, what is scheduled (each cancellable) and the last few
 * results. [exactAlarms] false warns that Android may run scheduled tasks late.
 */
@Composable
fun TasksCard(
    board: TaskBoard,
    exactAlarms: Boolean,
    onCancel: (Long) -> Unit,
    onRunNow: (Long) -> Unit,
    onClearRecent: () -> Unit,
    onAllowExactAlarms: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by rememberTickingNow()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.animateContentSize().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Tasks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                if (board.recent.isNotEmpty()) TextButton(onClick = onClearRecent) { Text("Clear finished") }
            }
            if (!exactAlarms && board.scheduled.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = WakeyColors.Amber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Exact alarms are off, so scheduled tasks may run late.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAllowExactAlarms) { Text("Allow") }
                }
            }
            PendingTasks(board, now, onCancel = onCancel)
            val recent = board.recent.take(RECENT_SHOWN)
            if (recent.isNotEmpty()) {
                TaskSectionTitle("Recent")
                RecentTasks(recent, now, onRunNow = onRunNow)
            }
        }
    }
}

private const val RECENT_SHOWN = 3
