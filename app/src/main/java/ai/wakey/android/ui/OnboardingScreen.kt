package ai.wakey.android.ui

import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.ui.components.AssistantOrb
import ai.wakey.android.ui.components.LockedPhoneNote
import ai.wakey.android.ui.components.MicrophoneSetupItem
import ai.wakey.android.ui.components.NotificationsSetupItem
import ai.wakey.android.ui.components.PRIVACY_LINE
import ai.wakey.android.ui.components.ScreenControlSetupItem
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val STEPS = 3

/** First launch: what Wakey is, then voice permissions, then optional screen control. */
@Composable
fun OnboardingScreen(setup: WakeySetup, onFinish: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step > 0) { step-- }
    val status = setup.status

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StepDots(step, Modifier.padding(top = 16.dp))
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
            },
            label = "onboardingStep",
        ) { current ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    when (current) {
                        0 -> WelcomeStep()
                        1 -> VoiceStep(setup)
                        else -> ScreenControlStep(setup)
                    }
                }
            }
        }
        Row(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            val (label, action) = when (step) {
                0 -> "Get started" to { step = 1 }
                1 -> (if (status.microphone) "Continue" else "Skip for now") to { step = 2 }
                else -> (if (status.screenControl) "Finish" else "Skip for now") to onFinish
            }
            Button(onClick = action) { Text(label) }
        }
    }
}

@Composable
private fun StepDots(step: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Step ${step + 1} of $STEPS" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(STEPS) { index ->
            val width by animateDpAsState(if (index == step) 24.dp else 8.dp, label = "dotWidth")
            val color by animateColorAsState(
                if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "dotColor",
            )
            Box(Modifier.size(width = width, height = 8.dp).background(color, CircleShape))
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ColumnScope.WelcomeStep() {
    AssistantOrb(
        phase = AssistantPhase.WakeListening,
        micLevel = { 0f },
        action = null,
        onTap = null,
        size = 160.dp,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
        "Hi, I'm Wakey",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
    Text(
        "Your hands-free helper for this phone. Say “Hey Wakey” or tap the mic, then ask in English, Hindi or a mix.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Feature(Icons.Rounded.Mic, "Talk or type", "Open apps, switch the flashlight on, or ask something quick.")
    Feature(Icons.Rounded.TouchApp, "Do things in other apps", "With screen control on, Wakey taps, types and scrolls for you, and shows each step.")
    Feature(Icons.Rounded.VerifiedUser, "You stay in charge", "Wakey asks before sending messages, buying things or changing account settings. Stop works any time.")
    Feature(Icons.Rounded.Lock, "Private by default", PRIVACY_LINE)
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VoiceStep(setup: WakeySetup) {
    StepTitle(
        "Let Wakey hear you",
        "Allow the microphone so Wakey can hear the wake word and your requests, and notifications so you can " +
            "stop it or approve actions from anywhere.",
    )
    MicrophoneSetupItem(setup)
    NotificationsSetupItem(setup)
}

@Composable
private fun ScreenControlStep(setup: WakeySetup) {
    StepTitle(
        "Let Wakey use your screen",
        "Optional. Without it Wakey can still open apps, use the flashlight and answer questions.",
    )
    ScreenControlSetupItem(setup)
    LockedPhoneNote()
}
