package ai.wakey.android.ui.components

import ai.wakey.android.config.TtsEngine
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AssistantController
import ai.wakey.android.tts.VoiceOption
import ai.wakey.android.ui.indianEnglishFirst
import ai.wakey.android.ui.theme.WakeyColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

const val HINDI_VOICE_NOTE = "Deepgram has no Hindi voice — Hindi (Devanagari) replies use the Android Hindi voice."

/** Voices offered by both engines, fetched once per [rememberVoiceCatalog] key. */
@Immutable
data class VoiceCatalog(val deepgram: List<VoiceOption>, val android: List<VoiceOption>) {
    fun currentLabel(settings: WakeySettings): String = when (settings.ttsEngine) {
        TtsEngine.Deepgram -> deepgram.firstOrNull { it.id == settings.deepgramVoice }?.label ?: settings.deepgramVoice
        TtsEngine.Android -> settings.androidVoiceName
            ?.let { name -> android.firstOrNull { it.id == name }?.label ?: name }
            ?: "Automatic"
    }
}

/**
 * Android voices appear once its TTS engine has started, so callers pass a [refreshKey] that
 * changes when the list is about to be shown (e.g. the picker opening).
 */
@Composable
fun rememberVoiceCatalog(controller: AssistantController, refreshKey: Any? = Unit): VoiceCatalog =
    remember(controller, refreshKey) {
        VoiceCatalog(deepgram = indianEnglishFirst(controller.deepgramVoices()), android = controller.androidVoices())
    }

private fun TtsEngine.shortLabel() = when (this) {
    TtsEngine.Android -> "Android"
    TtsEngine.Deepgram -> "Deepgram"
}

private fun languageName(tag: String): String =
    Locale.forLanguageTag(tag).displayName.ifBlank { tag }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsEngineSelector(selected: TtsEngine, onSelect: (TtsEngine) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        TtsEngine.entries.forEachIndexed { index, engine ->
            SegmentedButton(
                selected = engine == selected,
                onClick = { onSelect(engine) },
                shape = SegmentedButtonDefaults.itemShape(index, TtsEngine.entries.size),
                colors = monoSegmentColors(),
                label = { Text(engine.shortLabel(), maxLines = 1) },
            )
        }
    }
}

/** The chosen segment filled white, the others outlined. */
@Composable
internal fun monoSegmentColors(): SegmentedButtonColors = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primary,
    activeContentColor = MaterialTheme.colorScheme.onPrimary,
    activeBorderColor = MaterialTheme.colorScheme.primary,
    inactiveContainerColor = Color.Transparent,
    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
    inactiveBorderColor = MaterialTheme.colorScheme.outline,
)

/** Preview turns into Stop while anything is being spoken, so a long sample can be cut short. */
@Composable
fun PreviewVoiceButton(speaking: Boolean, onPreview: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = if (speaking) onStop else onPreview, modifier = modifier) {
        Icon(if (speaking) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (speaking) "Stop" else "Preview", maxLines = 1)
    }
}

/** The dashboard's voice switch: engine, current voice (opens the picker) and preview. */
@Composable
fun QuickVoiceControls(
    settings: WakeySettings,
    voiceLabel: String,
    speaking: Boolean,
    onEngineChange: (TtsEngine) -> Unit,
    onOpenPicker: () -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Voice", style = MaterialTheme.typography.bodyLarge)
        TtsEngineSelector(settings.ttsEngine, onEngineChange)
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = onOpenPicker,
                label = { Text(voiceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.RecordVoiceOver,
                        contentDescription = "Current voice",
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) },
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            PreviewVoiceButton(speaking, onPreview, onStopPreview)
        }
        if (settings.ttsEngine == TtsEngine.Deepgram) NoteText(HINDI_VOICE_NOTE, icon = Icons.Rounded.Info)
    }
}

/**
 * Radio list of voices. For Android, [automaticLabel] adds the "best offline voice" choice
 * (stored as a null voice name). Long lists collapse after [collapsedCount] rows.
 */
@Composable
fun VoiceChoiceList(
    voices: List<VoiceOption>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    automaticLabel: String? = null,
    showOfflineBadge: Boolean = false,
    collapsedCount: Int = Int.MAX_VALUE,
) {
    var showAll by remember { mutableStateOf(false) }
    val selectedIndex = voices.indexOfFirst { it.id == selectedId }
    // Never hide the selected voice behind "Show all".
    val visibleCount = if (showAll) voices.size else maxOf(collapsedCount, selectedIndex + 1)
    Column(modifier.selectableGroup()) {
        if (automaticLabel != null) {
            RadioRow(
                title = automaticLabel,
                subtitle = "Picks an installed offline voice for each reply's language",
                selected = selectedId == null,
                onClick = { onSelect(null) },
            )
        }
        voices.take(visibleCount).forEach { voice ->
            RadioRow(
                title = voice.label,
                subtitle = languageName(voice.languageTag),
                selected = voice.id == selectedId,
                onClick = { onSelect(voice.id) },
                trailing = if (showOfflineBadge) {
                    {
                        if (voice.offline) Pill("Offline", WakeyColors.Success)
                        else Pill("Needs internet", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    null
                },
            )
        }
        if (voices.isEmpty()) {
            NoteText(
                if (showOfflineBadge) "No installed voices found yet. You can add voices in Android Settings → Text-to-speech."
                else "No voices available.",
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (voices.size > visibleCount) {
            TextButton(onClick = { showAll = true }) { Text("Show all ${voices.size} voices") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(
    settings: WakeySettings,
    catalog: VoiceCatalog,
    speaking: Boolean,
    onEngineChange: (TtsEngine) -> Unit,
    onDeepgramVoice: (String) -> Unit,
    onAndroidVoice: (String?) -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose a voice", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            TtsEngineSelector(settings.ttsEngine, onEngineChange)
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when (settings.ttsEngine) {
                TtsEngine.Deepgram -> VoiceChoiceList(catalog.deepgram, settings.deepgramVoice, { id -> id?.let(onDeepgramVoice) })
                TtsEngine.Android -> VoiceChoiceList(
                    catalog.android,
                    settings.androidVoiceName,
                    onAndroidVoice,
                    automaticLabel = "Automatic (best offline voice)",
                    showOfflineBadge = true,
                )
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings.ttsEngine == TtsEngine.Deepgram) NoteText(HINDI_VOICE_NOTE, icon = Icons.Rounded.Info)
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                PreviewVoiceButton(speaking, onPreview, onStopPreview)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }) { Text("Done") }
            }
        }
    }
}
