package ai.wakey.android.ui.components

import ai.wakey.android.core.ChatEntry
import ai.wakey.android.core.Speaker
import ai.wakey.android.ui.formatTimingSummary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val UserBubble = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
private val WakeyBubble = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)

/** One conversation line: the user on the right, Wakey on the left, system notes centred. */
@Composable
fun ChatEntryRow(entry: ChatEntry, modifier: Modifier = Modifier) {
    when (entry.speaker) {
        Speaker.User -> UserMessage(entry, modifier)
        Speaker.Wakey -> WakeyMessage(entry, modifier)
        Speaker.System -> Text(
            entry.text,
            modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UserMessage(entry: ChatEntry, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
        Surface(shape = UserBubble, color = MaterialTheme.colorScheme.primaryContainer) {
            SelectionContainer {
                Text(
                    entry.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        entry.source?.let { source ->
            Text(
                source.label,
                modifier = Modifier.padding(top = 3.dp, end = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WakeyMessage(entry: ChatEntry, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(end = 48.dp), horizontalAlignment = Alignment.Start) {
        Surface(
            shape = WakeyBubble,
            color = if (entry.isError) colors.errorContainer else colors.surfaceContainerHigh,
            contentColor = if (entry.isError) colors.onErrorContainer else colors.onSurface,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                if (entry.isError) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = "Problem",
                        tint = colors.error,
                        modifier = Modifier.padding(top = 2.dp).size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                SelectionContainer {
                    Text(entry.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        entry.timings?.let(::formatTimingSummary)?.takeIf { it.isNotEmpty() }?.let { summary ->
            Text(
                summary,
                modifier = Modifier.padding(top = 4.dp, start = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** Shown before the first request: tappable examples that exercise the main flows. */
@Composable
fun ConversationHint(examples: List<String>, onExample: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NoteText("Try asking", icon = Icons.Rounded.Lightbulb, modifier = Modifier.padding(horizontal = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(examples) { example ->
                SuggestionChip(onClick = { onExample(example) }, label = { Text(example) })
            }
        }
    }
}
