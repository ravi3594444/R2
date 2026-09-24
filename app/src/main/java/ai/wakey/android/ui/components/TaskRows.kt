package ai.wakey.android.ui.components

import ai.wakey.android.tasks.TaskBoard
import ai.wakey.android.tasks.TaskKind
import ai.wakey.android.tasks.TaskReplies
import ai.wakey.android.tasks.TaskStatus
import ai.wakey.android.tasks.WakeyTask
import ai.wakey.android.ui.taskStatusLine
import ai.wakey.android.ui.theme.WakeyColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/** The current time, refreshed every [periodMs] so countdowns in task lists stay current. */
@Composable
fun rememberTickingNow(periodMs: Long = 15_000L): State<ZonedDateTime> = produceState(ZonedDateTime.now(), periodMs) {
    while (true) {
        delay(periodMs)
        value = ZonedDateTime.now()
    }
}

/** A small heading above a group of tasks, e.g. "Scheduled · 3". */
@Composable
fun TaskSectionTitle(title: String, modifier: Modifier = Modifier, count: Int? = null) {
    Text(
        if (count != null) "$title · $count" else title,
        modifier = modifier.padding(top = 4.dp, bottom = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * One task: an icon for its kind and state, what it is, when it runs or how it ended, and Cancel
 * (pending tasks) or Run now (missed and failed ones) when those are offered.
 */
@Composable
fun TaskRow(
    task: WakeyTask,
    now: ZonedDateTime,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    onRunNow: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        TaskIcon(task)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (task.status == TaskStatus.Cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                taskStatusLine(task, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            onRunNow != null -> IconButton(onClick = onRunNow) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Run “${task.title}” now", tint = MaterialTheme.colorScheme.primary)
            }
            onCancel != null -> IconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel “${task.title}”", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TaskIcon(task: WakeyTask) {
    val (icon, tint) = taskIcon(task)
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.14f), modifier = Modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center) {
            if (task.status == TaskStatus.Running) {
                CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
            } else {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun taskIcon(task: WakeyTask): Pair<ImageVector, Color> {
    val kindIcon = when (task.kind) {
        TaskKind.Task -> Icons.Rounded.Bolt
        TaskKind.Reminder -> Icons.Rounded.NotificationsActive
        TaskKind.Alarm -> if (task.text == TaskReplies.TIMER) Icons.Rounded.Timer else Icons.Rounded.Alarm
    }
    return when (task.status) {
        TaskStatus.Scheduled -> kindIcon to MaterialTheme.colorScheme.primary
        TaskStatus.Queued -> Icons.Rounded.Schedule to WakeyColors.Lilac
        TaskStatus.WaitingForUnlock -> Icons.Rounded.Lock to WakeyColors.Amber
        TaskStatus.Running -> kindIcon to WakeyColors.Amber
        TaskStatus.Done -> Icons.Rounded.CheckCircle to WakeyColors.Success
        TaskStatus.Failed -> Icons.Rounded.ErrorOutline to WakeyColors.Failure
        TaskStatus.Missed -> Icons.Rounded.ErrorOutline to WakeyColors.Amber
        TaskStatus.Cancelled -> Icons.Rounded.Cancel to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** Rows for everything on [board] that is still to come, grouped, for the dashboard and the floating panel. */
@Composable
fun PendingTasks(
    board: TaskBoard,
    now: ZonedDateTime,
    onCancel: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        val next = board.upNext + board.waiting
        if (next.isNotEmpty()) {
            TaskSectionTitle("Up next", count = next.size)
            next.forEach { task -> TaskRow(task, now, onCancel = { onCancel(task.id) }) }
        }
        if (board.scheduled.isNotEmpty()) {
            TaskSectionTitle("Scheduled", count = board.scheduled.size)
            board.scheduled.forEach { task -> TaskRow(task, now, onCancel = { onCancel(task.id) }) }
        }
    }
}

/** Finished tasks; missed and failed tasks can be run again. */
@Composable
fun RecentTasks(
    tasks: List<WakeyTask>,
    now: ZonedDateTime,
    onRunNow: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        tasks.forEach { task ->
            val retry = task.kind == TaskKind.Task && (task.status == TaskStatus.Missed || task.status == TaskStatus.Failed)
            TaskRow(task, now, onRunNow = if (retry) ({ onRunNow(task.id) }) else null)
        }
    }
}
