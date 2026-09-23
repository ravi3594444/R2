package ai.wakey.android.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val INFO_VISIBLE_MS = 6_000L

private data class Status(val message: String, val isError: Boolean)

/** The controller's status line. Information fades after a few seconds; errors stay until dismissed. */
@Composable
fun StatusBanner(message: String?, isError: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message, isError) {
        if (message != null && !isError) {
            delay(INFO_VISIBLE_MS)
            dismiss()
        }
    }
    AnimatedContent(
        targetState = message?.let { Status(it, isError) },
        modifier = modifier,
        transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = false) },
        label = "status",
    ) { status ->
        if (status == null) {
            Box(Modifier.fillMaxWidth())
        } else {
            val colors = MaterialTheme.colorScheme
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (status.isError) colors.errorContainer else colors.secondaryContainer,
                contentColor = if (status.isError) colors.onErrorContainer else colors.onSecondaryContainer,
            ) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (status.isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
                        contentDescription = null,
                        tint = if (status.isError) colors.error else colors.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        status.message,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 10.dp)
                            .semantics { liveRegion = if (status.isError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Dismiss") }
                }
            }
        }
    }
}
