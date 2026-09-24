package ai.wakey.android.ui.settings

import ai.wakey.android.config.SecretKind
import ai.wakey.android.core.AssistantController
import ai.wakey.android.ui.components.SectionCard
import ai.wakey.android.ui.components.StatusBadge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ApiKeysSection(controller: AssistantController) {
    SectionCard(
        "API keys",
        icon = Icons.Rounded.Key,
        subtitle = "Stored only on this phone, encrypted with an Android Keystore key. Wakey never shows a saved key in full.",
    ) {
        SecretKind.entries.forEachIndexed { index, kind ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ApiKeyEditor(kind, controller)
        }
    }
}

/**
 * One key: saved state, a masked field to replace it, Save / Clear and a live test. The draft is
 * deliberately not saveable state, so a typed key never lands in the saved-instance Bundle.
 */
@Composable
private fun ApiKeyEditor(kind: SecretKind, controller: AssistantController) {
    // Keys aren't observable; bump this after writing to re-read the saved state.
    var version by remember { mutableIntStateOf(0) }
    val saved = remember(version) { controller.hasApiKey(kind) }
    val hint = remember(version) { controller.apiKeyHint(kind) }
    var draft by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val save = {
        if (draft.isNotBlank()) {
            controller.saveApiKey(kind, draft)
            draft = ""
            version++
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(kind.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusBadge(ok = saved, text = if (saved) listOfNotNull("Saved", hint).joinToString(" ") else "Not set")
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (saved) "Replace key" else "Paste key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = save, enabled = draft.isNotBlank()) { Text("Save") }
            OutlinedButton(onClick = { confirmClear = true }, enabled = saved) { Text("Clear") }
        }
        when (kind) {
            SecretKind.LlmApiKey -> ConnectionTest("Test LLM", controller::testLlmConnection)
            SecretKind.DeepgramApiKey -> ConnectionTest("Test Deepgram", controller::testDeepgramConnection)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Remove ${kind.label}?") },
            text = { Text("Wakey will stop using it until you paste a key again.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.saveApiKey(kind, "")
                    confirmClear = false
                    version++
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
