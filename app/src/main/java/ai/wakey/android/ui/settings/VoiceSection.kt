package ai.wakey.android.ui.settings

import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AssistantController
import ai.wakey.android.ui.components.HINDI_VOICE_NOTE
import ai.wakey.android.ui.components.NoteText
import ai.wakey.android.ui.components.PreviewVoiceButton
import ai.wakey.android.ui.components.SectionCard
import ai.wakey.android.ui.components.SwitchRow
import ai.wakey.android.ui.components.TtsEngineSelector
import ai.wakey.android.ui.components.VoiceChoiceList
import ai.wakey.android.ui.components.rememberVoiceCatalog
import ai.wakey.android.ui.formatSpeechRate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

private const val COLLAPSED_VOICES = 6

@Composable
fun VoiceSection(controller: AssistantController, settings: WakeySettings, speaking: Boolean) {
    val catalog = rememberVoiceCatalog(controller)
    SectionCard("Voice", icon = Icons.Rounded.RecordVoiceOver, subtitle = "Which engine speaks Wakey's replies.") {
        TtsEngineSelector(settings.ttsEngine, onSelect = { engine -> controller.updateSettings { it.copy(ttsEngine = engine) } })
        NoteText(HINDI_VOICE_NOTE, icon = Icons.Rounded.Info)

        SubHeading("Deepgram voices")
        VoiceChoiceList(
            catalog.deepgram,
            selectedId = settings.deepgramVoice,
            onSelect = { id -> if (id != null) controller.updateSettings { it.copy(deepgramVoice = id) } },
            collapsedCount = COLLAPSED_VOICES,
        )

        SubHeading("Android voices")
        VoiceChoiceList(
            catalog.android,
            selectedId = settings.androidVoiceName,
            onSelect = { name -> controller.updateSettings { it.copy(androidVoiceName = name) } },
            automaticLabel = "Automatic (best offline voice)",
            showOfflineBadge = true,
            collapsedCount = COLLAPSED_VOICES,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SpeechRateSlider(settings.speechRate) { rate -> controller.updateSettings { it.copy(speechRate = rate) } }
        SwitchRow(
            title = "Speak replies",
            subtitle = "Off: replies are shown but not spoken.",
            checked = settings.speakReplies,
            onCheckedChange = { on -> controller.updateSettings { it.copy(speakReplies = on) } },
        )
        PreviewVoiceButton(speaking, onPreview = { controller.previewVoice() }, onStop = { controller.stop(silent = true) })
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SpeechRateSlider(saved: Float, onCommit: (Float) -> Unit) {
    var rate by remember(saved) { mutableFloatStateOf(saved) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text("Speech rate", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(formatSpeechRate(rate), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = rate,
            onValueChange = { rate = it },
            onValueChangeFinished = { onCommit(rate) },
            valueRange = 0.5f..2f,
            steps = 14,
            modifier = Modifier.semantics { contentDescription = "Speech rate" },
        )
    }
}
