package ai.wakey.android.ui.settings

import ai.wakey.android.config.WakeMode
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AssistantController
import ai.wakey.android.ui.components.NoteText
import ai.wakey.android.ui.components.SectionCard
import ai.wakey.android.ui.components.SwitchRow
import ai.wakey.android.ui.components.monoSegmentColors
import ai.wakey.android.ui.normalizeWakePhrase
import ai.wakey.android.wake.EncodedKeyword
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val PREVIEW_DEBOUNCE_MS = 250L

@Composable
fun WakeWordSection(controller: AssistantController, settings: WakeySettings) {
    var draft by rememberSaveable(settings.wakePhrase) { mutableStateOf(settings.wakePhrase) }
    val phrase = normalizeWakePhrase(draft)
    // (phrase, result) so a result is never shown against a newer draft.
    var preview by remember { mutableStateOf<Pair<String, Result<EncodedKeyword>>?>(null) }
    // Encoding loads the tokenizer from assets on first use; keep it off the main thread and one at a time.
    val encoderLock = remember { Mutex() }
    LaunchedEffect(phrase) {
        delay(PREVIEW_DEBOUNCE_MS)
        val result = if (phrase.isEmpty()) {
            Result.failure(IllegalArgumentException("Enter a wake phrase."))
        } else {
            withContext(Dispatchers.Default) { encoderLock.withLock { controller.previewWakePhrase(phrase) } }
        }
        preview = phrase to result
    }
    val current = preview?.takeIf { it.first == phrase }?.second
    val canApply = current?.isSuccess == true && phrase != settings.wakePhrase
    val apply = { if (canApply) controller.updateSettings { it.copy(wakePhrase = phrase) } }

    SectionCard(
        "Wake word",
        icon = Icons.Rounded.Hearing,
        subtitle = "How you start talking to Wakey hands-free.",
    ) {
        WakeModeSelector(settings.wakeMode) { mode -> controller.updateSettings { it.copy(wakeMode = mode) } }
        if (settings.wakeMode == WakeMode.HeyCommand) {
            NoteText(
                "Say “Hey” and your request in one go: “Hey, open Instagram”, “Hey, torch jalao”. " +
                    "Wakey acts only when a command follows “hey”, so “hey, how are you?” is ignored. " +
                    "After each “hey” it hears, the next few seconds of audio go to Deepgram to check. " +
                    "For now this catches fewer requests than “Hey Wakey” (about 7 in 10 in tests, against 9 in 10), " +
                    "and “Hey Wakey” keeps working in this mode too.",
            )
        } else {
            PhraseEditor(draft, onDraft = { draft = it }, phrase = phrase, current = current, canApply = canApply, apply = apply)
        }
        SensitivitySlider(settings.wakeSensitivity) { value -> controller.updateSettings { it.copy(wakeSensitivity = value) } }
        SwitchRow(
            title = "Boost a quiet microphone",
            subtitle = "Raises quiet speech automatically, so you can talk normally. Turn it off if Wakey reacts to background noise.",
            checked = settings.micBoost,
            onCheckedChange = { on -> controller.updateSettings { it.copy(micBoost = on) } },
        )
        SwitchRow(
            title = "Wake sound",
            subtitle = "A short chime when Wakey hears you.",
            checked = settings.wakeSound,
            onCheckedChange = { on -> controller.updateSettings { it.copy(wakeSound = on) } },
        )
        NoteText(
            "Detection runs on this phone. When it is unsure, the audio from just before is sent to " +
                "Deepgram to check; otherwise no audio leaves the phone until you speak to Wakey.",
            icon = Icons.Rounded.PhoneAndroid,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeModeSelector(selected: WakeMode, onSelect: (WakeMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        WakeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, WakeMode.entries.size),
                colors = monoSegmentColors(),
                label = { Text(mode.label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun PhraseEditor(
    draft: String,
    onDraft: (String) -> Unit,
    phrase: String,
    current: Result<EncodedKeyword>?,
    canApply: Boolean,
    apply: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Wake phrase") },
            singleLine = true,
            isError = current?.isFailure == true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { apply() }),
        )
        TokenPreview(current)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = apply, enabled = canApply) { Text("Apply") }
            if (phrase != WakeySettings.DEFAULT_WAKE_PHRASE) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onDraft(WakeySettings.DEFAULT_WAKE_PHRASE) }) { Text("Use “${WakeySettings.DEFAULT_WAKE_PHRASE}”") }
            }
        }
        NoteText("Short, distinct English phrases work best, like “Hey Wakey”.")
    }
}

/** The model's tokens for the phrase, e.g. "▁HE Y ▁WA KE Y", or why it can't be used. */
@Composable
private fun TokenPreview(result: Result<EncodedKeyword>?) {
    val modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
    when {
        result == null -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Checking phrase…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        result.isSuccess -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Model tokens", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(
                    result.getOrThrow().tokens.joinToString(" "),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        else -> Text(
            result.exceptionOrNull()?.message ?: "This phrase can't be used.",
            modifier = modifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Commits on release: every change rebuilds the detector's keyword graph. */
@Composable
private fun SensitivitySlider(saved: Float, onCommit: (Float) -> Unit) {
    var value by remember(saved) { mutableFloatStateOf(saved) }
    Column {
        Text("Sensitivity", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onCommit(value) },
            valueRange = 0f..1f,
            modifier = Modifier.semantics { contentDescription = "Wake word sensitivity" },
        )
        Row(Modifier.fillMaxWidth()) {
            Text("Fewer false wakes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("More sensitive", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
