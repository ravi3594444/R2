package ai.wakey.android.ui.components

import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.ui.theme.WakeyColors
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** True while there is something for Stop to cancel. */
val AssistantUiState.isBusy: Boolean
    get() = phase in BUSY_PHASES || currentAction != null || pendingConfirmation != null

private val BUSY_PHASES = setOf(AssistantPhase.Hearing, AssistantPhase.Thinking, AssistantPhase.Acting, AssistantPhase.Speaking)

/**
 * The orb, what Wakey is hearing or doing, and Stop. The orb is the talk button: tap to talk, tap
 * again to finish. [orbSize] null selects the compact one-row form used when the keyboard is open
 * or the screen is short.
 */
@Composable
fun AssistantHero(
    state: AssistantUiState,
    wakePhrase: String,
    micLevel: () -> Float,
    orbSize: Dp?,
    onOrbTap: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.isBusy
    val caption = heroCaption(state, wakePhrase)
    if (orbSize == null) {
        Row(
            modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantOrb(state.phase, micLevel, state.currentAction, onOrbTap, size = 64.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                caption.headline,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedVisibility(busy, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                StopButton(onStop, Modifier.padding(start = 12.dp))
            }
        }
    } else {
        Column(
            modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AssistantOrb(state.phase, micLevel, state.currentAction, onOrbTap, size = orbSize)
            AnimatedContent(
                targetState = caption,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                contentKey = { it.phase },
                label = "caption",
            ) { shown ->
                Column(
                    Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = 72.dp).padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        shown.headline,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = if (shown.isTranscript) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    shown.hint?.let { hint ->
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
            AnimatedVisibility(busy, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                StopButton(onStop, Modifier.padding(top = 8.dp, bottom = 4.dp))
            }
        }
    }
}

/** What the hero says: a big line (the live transcript while hearing) and an optional quiet hint. */
private data class HeroCaption(val phase: AssistantPhase, val headline: String, val hint: String?, val isTranscript: Boolean = false)

private fun heroCaption(state: AssistantUiState, wakePhrase: String): HeroCaption = when (state.phase) {
    AssistantPhase.Idle -> HeroCaption(state.phase, "Tap to talk", "Turn on “Listen for $wakePhrase” to talk hands-free")
    AssistantPhase.WakeListening -> HeroCaption(state.phase, "Say “$wakePhrase”", "or tap the orb")
    AssistantPhase.Hearing ->
        if (state.liveTranscript.isBlank()) HeroCaption(state.phase, "Listening…", "Tap the orb when you're done")
        else HeroCaption(state.phase, state.liveTranscript, null, isTranscript = true)
    AssistantPhase.Thinking -> HeroCaption(state.phase, "Thinking…", null)
    AssistantPhase.Acting -> HeroCaption(state.phase, state.currentAction?.description ?: "Working on it…", null)
    AssistantPhase.Speaking -> HeroCaption(state.phase, "Speaking…", null)
}

/** The one filled button while Wakey is busy: white on black, so it is the first thing the eye finds. */
@Composable
fun StopButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = WakeyColors.Stop, contentColor = WakeyColors.OnStop),
    ) {
        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Stop", style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
