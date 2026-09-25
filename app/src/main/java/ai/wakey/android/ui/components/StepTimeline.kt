package ai.wakey.android.ui.components

import ai.wakey.android.core.AgentActionInfo
import ai.wakey.android.ui.theme.WakeyColors
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A task's steps as a timeline. Each step slides in as it starts; its marker turns from a spinner
 * into a check or a cross when it finishes; while Wakey works out the next step a "planning" row
 * pulses at the end. Long runs show their latest steps, with the rest a tap away.
 *
 * [running] is true while the task is in progress; [steps] are its actions, oldest first.
 */
@Composable
fun StepTimeline(steps: List<AgentActionInfo>, running: Boolean, modifier: Modifier = Modifier) {
    if (steps.isEmpty() && !running) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val last = steps.lastOrNull()
    val deciding = running && (last == null || last.result != null)
    val failed = !running && last != null && last.success != true
    val hidden = if (expanded) 0 else (steps.size - MAX_SHOWN).coerceAtLeast(0)
    val border by animateColorAsState(
        when {
            running -> WakeyColors.White.copy(alpha = 0.4f)
            failed -> WakeyColors.Failure.copy(alpha = 0.35f)
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        tween(500),
        label = "timelineBorder",
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
            AnimatedVisibility(running, enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = WakeyColors.White,
                    trackColor = Color.Transparent,
                )
            }
            Column(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 6.dp)) {
                AnimatedContent(
                    targetState = header(steps, running, failed),
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    label = "timelineHeader",
                ) { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (running) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Spacer(Modifier.height(8.dp))
                AnimatedVisibility(hidden > 0) {
                    TextButton(onClick = { expanded = true }, modifier = Modifier.padding(start = 22.dp)) {
                        Text("Show ${hidden} earlier ${if (hidden == 1) "step" else "steps"}")
                        Icon(Icons.Rounded.ExpandMore, contentDescription = null)
                    }
                }
                steps.drop(hidden).forEachIndexed { index, step ->
                    key(step.step, step.toolName) {
                        val isLast = index == steps.size - hidden - 1
                        StepRow(step, active = running && step.result == null, connectBelow = !isLast || deciding)
                    }
                }
                AnimatedVisibility(
                    visible = deciding,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                ) {
                    PlanningRow(first = steps.isEmpty())
                }
                if (expanded && steps.size > MAX_SHOWN) {
                    TextButton(onClick = { expanded = false }, modifier = Modifier.padding(start = 22.dp)) {
                        Text("Show fewer steps")
                        Icon(Icons.Rounded.ExpandLess, contentDescription = null)
                    }
                }
            }
        }
    }
}

/** "Working · step 3", then "Done in 3 steps", or "Stopped after 3 steps" if the last step didn't succeed. */
private fun header(steps: List<AgentActionInfo>, running: Boolean, failed: Boolean): String {
    val count = steps.maxOfOrNull { it.step } ?: 0
    val plural = if (count == 1) "step" else "steps"
    return when {
        running && count == 0 -> "Working on it"
        running -> "Working · step $count"
        failed -> "Stopped after $count $plural"
        else -> "Done in $count $plural"
    }
}

/** One step: its marker on the line, what Wakey did, and how it went once known. */
@Composable
private fun StepRow(step: AgentActionInfo, active: Boolean, connectBelow: Boolean) {
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = appear,
        enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) +
            slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { -it / 3 } + fadeIn(tween(250)),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                StepMarker(step, active)
                val lineColor by animateColorAsState(
                    if (step.success == true) WakeyColors.Success.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
                    tween(400),
                    label = "stepLine",
                )
                if (connectBelow) {
                    Box(Modifier.padding(vertical = 2.dp).width(2.dp).weight(1f).background(lineColor, CircleShape))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(bottom = 12.dp)) {
                AnimatedContent(
                    targetState = step.description,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(100)) },
                    label = "stepText",
                ) { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    )
                }
                AnimatedVisibility(
                    visible = !step.result.isNullOrBlank(),
                    enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Text(
                        step.result.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (step.success == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepMarker(step: AgentActionInfo, active: Boolean) {
    val state = when {
        step.success == true -> MarkerState.Done
        step.success == false -> MarkerState.Failed
        active -> MarkerState.Running
        else -> MarkerState.Unfinished
    }
    Crossfade(targetState = state, animationSpec = tween(250), label = "stepMarker") { marker ->
        val color = when (marker) {
            MarkerState.Done -> WakeyColors.Success
            MarkerState.Failed -> WakeyColors.Failure
            MarkerState.Running -> WakeyColors.White
            MarkerState.Unfinished -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Surface(shape = CircleShape, color = color.copy(alpha = 0.16f), modifier = Modifier.size(22.dp)) {
            Box(contentAlignment = Alignment.Center) {
                when (marker) {
                    MarkerState.Running -> CircularProgressIndicator(Modifier.size(14.dp), color = color, strokeWidth = 2.dp)
                    MarkerState.Done -> PopIn { Icon(Icons.Rounded.Check, contentDescription = "Done", tint = color, modifier = Modifier.size(15.dp)) }
                    MarkerState.Failed -> PopIn { Icon(Icons.Rounded.Close, contentDescription = "Failed", tint = color, modifier = Modifier.size(15.dp)) }
                    MarkerState.Unfinished -> Icon(Icons.Rounded.Remove, contentDescription = "Not finished", tint = color, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

private enum class MarkerState { Running, Done, Failed, Unfinished }

@Composable
private fun PopIn(content: @Composable () -> Unit) {
    AnimatedVisibility(
        visibleState = remember { MutableTransitionState(false).apply { targetState = true } },
        enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
    ) { content() }
}

/** Shown while Wakey decides what to do next, so the gap between steps reads as work, not a stall. */
@Composable
private fun PlanningRow(first: Boolean) {
    val pulse by rememberInfiniteTransition(label = "planning").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "planningPulse",
    )
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).graphicsLayer { alpha = pulse }.background(WakeyColors.Muted, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        AnimatedContent(
            targetState = if (first) "Looking at the screen…" else "Working out the next step…",
            transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 }) },
            label = "planningText",
        ) { text ->
            Text(
                text,
                modifier = Modifier.graphicsLayer { alpha = 0.6f + 0.4f * pulse },
                style = MaterialTheme.typography.bodyMedium,
                color = WakeyColors.Muted,
            )
        }
    }
}

private const val MAX_SHOWN = 5
