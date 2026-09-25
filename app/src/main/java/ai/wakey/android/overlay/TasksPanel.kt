package ai.wakey.android.overlay

import ai.wakey.android.core.AgentActionInfo
import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.tasks.TaskBoard
import ai.wakey.android.ui.components.PendingTasks
import ai.wakey.android.ui.components.RecentTasks
import ai.wakey.android.ui.components.TaskSectionTitle
import ai.wakey.android.ui.theme.WakeyColors
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.ZonedDateTime

/**
 * The floating button's task panel: what runs now (with Stop), what's next, what's scheduled (each
 * cancellable), the last few results, and buttons to talk or open Wakey.
 */
@Composable
internal fun TasksPanel(
    board: TaskBoard,
    phase: AssistantPhase,
    currentAction: AgentActionInfo?,
    now: ZonedDateTime,
    width: Dp,
    maxHeight: Dp,
    onCancelTask: (Long) -> Unit,
    onRunNow: (Long) -> Unit,
    onStop: () -> Unit,
    onTalk: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(8.dp).width(width),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.heightIn(max = maxHeight)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Wakey tasks", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                PhaseLabel(phase, Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close tasks") }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                board.running?.let { running ->
                    TaskSectionTitle("Now")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = WakeyColors.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(running.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            AnimatedContent(
                                targetState = currentAction?.description ?: "Thinking…",
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "panelStep",
                            ) { step ->
                                Text(
                                    step,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(containerColor = WakeyColors.Stop, contentColor = WakeyColors.OnStop),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop")
                        }
                    }
                }
                PendingTasks(board, now, onCancel = onCancelTask)
                val recent = board.recent.take(RECENT_SHOWN)
                if (recent.isNotEmpty()) {
                    TaskSectionTitle("Recent")
                    RecentTasks(recent, now, onRunNow = onRunNow)
                }
                if (board.running == null && board.pendingCount == 0 && recent.isEmpty()) {
                    Text(
                        "Nothing yet. Tap the button and say “Call mum at 4”, “Remind me to stretch in 10 minutes” or " +
                            "“Play some music after this”.",
                        modifier = Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(4.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTalk, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Talk", maxLines = 1)
                }
                OutlinedButton(onClick = onOpenApp, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open Wakey", maxLines = 1)
                }
            }
        }
    }
}

private const val RECENT_SHOWN = 3

/** The phase as a quiet "● Listening": the dot is grey at rest and white while Wakey works, like the orb. */
@Composable
private fun PhaseLabel(phase: AssistantPhase, modifier: Modifier = Modifier) {
    val color by animateColorAsState(WakeyColors.phase(phase), label = "panelPhase")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            phase.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
