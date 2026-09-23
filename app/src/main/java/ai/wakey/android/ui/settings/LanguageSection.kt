package ai.wakey.android.ui.settings

import ai.wakey.android.config.LanguageMode
import ai.wakey.android.ui.components.RadioRow
import ai.wakey.android.ui.components.SectionCard
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private fun LanguageMode.description() = when (this) {
    LanguageMode.EnglishHindi -> "Best for Hinglish and switching mid-sentence"
    LanguageMode.English -> "Only English is expected"
    LanguageMode.Hindi -> "Only Hindi is expected"
    LanguageMode.Auto -> "Deepgram decides from what it hears"
}

@Composable
fun LanguageSection(selected: LanguageMode, onChange: (LanguageMode) -> Unit) {
    SectionCard("Language", icon = Icons.Rounded.Translate, subtitle = "What you'll speak to Wakey.") {
        Column(Modifier.selectableGroup()) {
            LanguageMode.entries.forEach { mode ->
                RadioRow(
                    title = mode.label,
                    subtitle = mode.description(),
                    selected = mode == selected,
                    onClick = { onChange(mode) },
                )
            }
        }
    }
}
