package ai.wakey.android.ui.settings

import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AssistantController
import ai.wakey.android.ui.components.NoteText
import ai.wakey.android.ui.components.SectionCard
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.roundToInt

private const val MIN_STEPS = 3

@Composable
fun ModelSection(controller: AssistantController, settings: WakeySettings) {
    var baseUrl by rememberSaveable(settings.llmBaseUrl) { mutableStateOf(settings.llmBaseUrl) }
    var model by rememberSaveable(settings.llmModel) { mutableStateOf(settings.llmModel) }
    val cleanUrl = baseUrl.trim().trimEnd('/')
    val cleanModel = model.trim()
    // The app allows no cleartext traffic, so an http:// endpoint could never work.
    val urlValid = cleanUrl.startsWith("https://") && cleanUrl.length > "https://".length
    val changed = cleanUrl != settings.llmBaseUrl || cleanModel != settings.llmModel

    SectionCard(
        "AI model",
        icon = Icons.Rounded.SmartToy,
        subtitle = "Any OpenAI-compatible endpoint with tool calling. Only used when a request needs more than a direct command.",
    ) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            singleLine = true,
            isError = !urlValid,
            supportingText = { Text(if (urlValid) "e.g. ${WakeySettings.DEFAULT_LLM_BASE_URL}" else "Use an https:// address") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model ID") },
            singleLine = true,
            isError = cleanModel.isEmpty(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        )
        Button(
            onClick = { controller.updateSettings { it.copy(llmBaseUrl = cleanUrl, llmModel = cleanModel) } },
            enabled = changed && urlValid && cleanModel.isNotEmpty(),
        ) { Text("Save") }

        MaxStepsSlider(settings.maxAgentSteps) { steps -> controller.updateSettings { it.copy(maxAgentSteps = steps) } }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Fast decisions with Jev", style = MaterialTheme.typography.bodyLarge)
                NoteText("Jev (via AI/ML API) picks routine taps in about half a second; the AI model takes over when it's unsure. Needs the AI/ML API key below.")
            }
            Switch(
                checked = settings.useFastDecisions,
                onCheckedChange = { on -> controller.updateSettings { it.copy(useFastDecisions = on) } },
            )
        }

        ConnectionTest("Test connection", controller::testLlmConnection)
        if (changed) NoteText("The test uses the saved settings. Save first to test your changes.")
    }
}

@Composable
private fun MaxStepsSlider(saved: Int, onCommit: (Int) -> Unit) {
    val max = WakeySettings.MAX_AGENT_STEPS_LIMIT
    var steps by remember(saved) { mutableFloatStateOf(saved.coerceIn(MIN_STEPS, max).toFloat()) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text("Max steps per task", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text("${steps.roundToInt()}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = steps,
            onValueChange = { steps = it },
            onValueChangeFinished = { onCommit(steps.roundToInt()) },
            valueRange = MIN_STEPS.toFloat()..max.toFloat(),
            steps = max - MIN_STEPS - 1,
            modifier = Modifier.semantics { contentDescription = "Max steps per task" },
        )
        NoteText("Wakey stops a task after this many screen actions, so a confused run can't go on and on.")
    }
}
