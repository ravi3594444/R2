package ai.wakey.android.ui.components

import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.ui.theme.WakeyColors
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** True while there is something for Stop to cancel. */
val AssistantUiState.isBusy: Boolean
    get() = phase in BUSY_PHASES || currentAction != null || pendingConfirmation != null || taskRunning

private val BUSY_PHASES = setOf(AssistantPhase.Hearing, AssistantPhase.Thinking, AssistantPhase.Acting, AssistantPhase.Speaking)

/**
 * The orb, what Wakey is hearing or doing, hold-to-talk and Stop. [orbSize] null selects the
 * compact one-row form used when the keyboard is open or the screen is short.
 */
@Composable
fun AssistantHero(
    state: AssistantUiState,
    wakePhrase: String,
    micLevel: () -> Float,
    orbSize: Dp?,
    onOrbTap: () -> Unit,
    onPushToTalkStart: () -> Boolean,
    onPushToTalkEnd: () -> Unit,
    onTalkClick: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.isBusy
    if (orbSize == null) {
        Row(
            modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantOrb(state.phase, micLevel, state.currentAction, onOrbTap, size = 64.dp)
            Spacer(Modifier.width(12.dp))
            HeroCaption(state, wakePhrase, compact = true, modifier = Modifier.weight(1f), maxLines = 2, textAlign = TextAlign.Start)
            AnimatedVisibility(busy, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                StopButton(onStop, Modifier.padding(start = 12.dp))
            }
        }
    } else {
        Column(
            modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AssistantOrb(state.phase, micLevel, state.currentAction, onOrbTap, size = orbSize)
            HeroCaption(
                state,
                wakePhrase,
                compact = false,
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
                maxLines = 3,
                textAlign = TextAlign.Center,
            )
            Row(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HoldToTalkButton(
                    active = state.pushToTalkActive,
                    onPressStart = onPushToTalkStart,
                    onPressEnd = onPushToTalkEnd,
                    onAccessibleClick = onTalkClick,
                    modifier = Modifier.weight(1f),
                )
                AnimatedVisibility(busy, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                    StopButton(onStop, Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun HeroCaption(
    state: AssistantUiState,
    wakePhrase: String,
    compact: Boolean,
    modifier: Modifier,
    maxLines: Int,
    textAlign: TextAlign,
) {
    val hearing = state.phase == AssistantPhase.Hearing
    val text = when (state.phase) {
        AssistantPhase.Idle -> if (compact) "Tap the mic to talk" else "Tap the mic or hold to talk"
        AssistantPhase.WakeListening -> "Say “$wakePhrase”, or tap the mic"
        AssistantPhase.Hearing -> state.liveTranscript.ifBlank { "Listening…" }
        AssistantPhase.Thinking -> "Thinking…"
        AssistantPhase.Acting -> state.currentAction?.description ?: "Working on it…"
        AssistantPhase.Speaking -> "Speaking…"
    }
    // Captions slide in as the step changes; a live transcript updates in place instead.
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        contentKey = { if (hearing) AssistantPhase.Hearing else it },
        contentAlignment = if (textAlign == TextAlign.Center) Alignment.Center else Alignment.CenterStart,
        transitionSpec = {
            (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 3 }) togetherWith
                (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 3 }) using SizeTransform(clip = false)
        },
        label = "heroCaption",
    ) { caption ->
        Text(
            caption,
            modifier = Modifier.fillMaxWidth(),
            style = if (hearing && state.liveTranscript.isNotBlank()) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            color = if (hearing) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Press and hold to stream, release to send. [onPressStart] returns false when it could not start
 * (e.g. it asked for the microphone permission instead), in which case release is ignored.
 * TalkBack users get a plain double-tap action, [onAccessibleClick], since holding is awkward there.
 */
@Composable
fun HoldToTalkButton(
    active: Boolean,
    onPressStart: () -> Boolean,
    onPressEnd: () -> Unit,
    onAccessibleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val start by rememberUpdatedState(onPressStart)
    val end by rememberUpdatedState(onPressEnd)
    var pressed by remember { mutableStateOf(false) }
    val highlighted = pressed || active
    val container by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "pttContainer",
    )
    val content by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        label = "pttContent",
    )
    val scale by animateFloatAsState(if (highlighted) 1.04f else 1f, label = "pttScale")
    Surface(
        modifier = modifier
            .heightIn(min = 56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = "Hold to talk"
                onClick(label = "talk") { onAccessibleClick(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (start()) {
                            pressed = true
                            try {
                                tryAwaitRelease()
                            } finally {
                                pressed = false
                                end()
                            }
                        }
                    },
                )
            },
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (highlighted) Icons.Rounded.GraphicEq else Icons.Rounded.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (highlighted) "Release to send" else "Hold to talk",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun StopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = WakeyColors.Stop, contentColor = WakeyColors.OnStop),
    ) {
        Icon(Icons.Rounded.Stop, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Stop", style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
