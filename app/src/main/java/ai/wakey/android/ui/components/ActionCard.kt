package ai.wakey.android.ui.components

import ai.wakey.android.core.AgentActionInfo
import ai.wakey.android.ui.theme.WakeyColors
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * What the agent is doing now ([current]), or did last, with the task's steps ([recent]) expandable.
 */
@Composable
fun ActionCard(current: AgentActionInfo?, recent: List<AgentActionInfo>, modifier: Modifier = Modifier) {
    val headline = current ?: recent.lastOrNull() ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                if (current != null) "Step ${headline.step} of ${headline.maxSteps}" else "Last task · ${recent.size} ${if (recent.size == 1) "step" else "steps"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            ActionLine(headline, running = current != null, prominent = true, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (recent.size > 1) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide steps" else "Show all ${recent.size} steps")
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
            AnimatedVisibility(expanded && recent.size > 1) {
                Column(Modifier.padding(end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    recent.forEach { action -> ActionLine(action, running = current != null, prominent = false) }
                }
            }
        }
    }
}

@Composable
private fun ActionLine(action: AgentActionInfo, running: Boolean, prominent: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).size(20.dp), contentAlignment = Alignment.Center) {
            when {
                action.success == true -> Icon(Icons.Rounded.CheckCircle, contentDescription = "Succeeded", tint = WakeyColors.Success)
                action.success == false -> Icon(Icons.Rounded.Cancel, contentDescription = "Failed", tint = WakeyColors.Failure)
                running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else -> Icon(
                    Icons.Rounded.RemoveCircleOutline,
                    contentDescription = "Not finished",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (prominent) action.description else "${action.step}. ${action.description}",
                style = if (prominent) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            )
            action.result?.takeIf { it.isNotBlank() }?.let { result ->
                Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
