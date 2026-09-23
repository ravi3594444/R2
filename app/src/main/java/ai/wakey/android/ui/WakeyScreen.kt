package ai.wakey.android.ui

import ai.wakey.android.config.TtsEngine
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AssistantController
import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.core.Speaker
import ai.wakey.android.ui.components.ActionCard
import ai.wakey.android.ui.components.AssistantHero
import ai.wakey.android.ui.components.ChatEntryRow
import ai.wakey.android.ui.components.ConversationHint
import ai.wakey.android.ui.components.InputBar
import ai.wakey.android.ui.components.NoteText
import ai.wakey.android.ui.components.PRIVACY_LINE
import ai.wakey.android.ui.components.PhaseChip
import ai.wakey.android.ui.components.QuickVoiceControls
import ai.wakey.android.ui.components.SetupNeededCard
import ai.wakey.android.ui.components.StatusBanner
import ai.wakey.android.ui.components.SwitchRow
import ai.wakey.android.ui.components.VoicePickerSheet
import ai.wakey.android.ui.components.rememberVoiceCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val EXAMPLES = listOf(
    "Open YouTube",
    "Turn on the flashlight",
    "Open Settings and find Bluetooth",
    "Open Chrome and search for cats",
)

/** The dashboard: orb and talk controls on top, then setup, quick controls, the task and the conversation. */
@Composable
fun WakeyScreen(
    controller: AssistantController,
    state: AssistantUiState,
    micLevel: () -> Float,
    settings: WakeySettings,
    setup: WakeySetup,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val context = LocalContext.current
    var voicePickerOpen by rememberSaveable { mutableStateOf(false) }
    var setupReminderHidden by rememberSaveable { mutableStateOf(false) }
    val catalog = rememberVoiceCatalog(controller, refreshKey = voicePickerOpen)
    val speaking = state.phase == AssistantPhase.Speaking

    val talk = { setup.withMicrophone { controller.onMicTap() } }
    val setWakeListening = { enabled: Boolean ->
        if (enabled) setup.withMicrophone(forWakeWord = true) { controller.setWakeListening(context, true) }
        else controller.setWakeListening(context, false)
    }
    val setEngine = { engine: TtsEngine -> controller.updateSettings { it.copy(ttsEngine = engine) } }
    val preview = { controller.previewVoice() }
    val stopPreview = { controller.stop(silent = true) }

    Scaffold(
        topBar = { WakeyTopBar(state.phase, onOpenSettings) },
        bottomBar = { InputBar(onSend = controller::submitText) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val orbSize = heroOrbSize(maxHeight)
            Column(Modifier.fillMaxSize()) {
                StatusBanner(state.statusMessage, state.statusIsError, onDismiss = controller::dismissStatus)
                AssistantHero(
                    state = state,
                    wakePhrase = settings.wakePhrase,
                    micLevel = micLevel,
                    orbSize = orbSize,
                    onOrbTap = talk,
                    onPushToTalkStart = {
                        if (setup.status.microphone) {
                            controller.onPushToTalkPressed()
                            true
                        } else {
                            setup.withMicrophone {}
                            false
                        }
                    },
                    onPushToTalkEnd = controller::onPushToTalkReleased,
                    onTalkClick = talk,
                    onStop = { controller.stop() },
                )
                val showSetupReminder = setup.status.needsAttention && !setupReminderHidden
                val showHint = state.entries.isEmpty()
                Feed(
                    state = state,
                    modifier = Modifier.weight(1f),
                    headerCount = 1 + (if (showSetupReminder) 1 else 0) + (if (showHint) 1 else 0),
                    header = {
                        if (showSetupReminder) {
                            item(key = "setup") {
                                SetupNeededCard(
                                    status = setup.status,
                                    onAllowMicrophone = { setup.withMicrophone {} },
                                    onEnableScreenControl = setup::openAccessibilitySettings,
                                    onOpenSetup = onOpenSetup,
                                    onHide = { setupReminderHidden = true },
                                )
                            }
                        }
                        item(key = "controls") {
                            QuickControlsCard(
                                settings = settings,
                                wakeRunning = state.wakeServiceRunning,
                                onWakeListeningChange = setWakeListening,
                                voiceLabel = catalog.currentLabel(settings),
                                speaking = speaking,
                                onEngineChange = setEngine,
                                onOpenVoicePicker = { voicePickerOpen = true },
                                onPreview = preview,
                                onStopPreview = stopPreview,
                            )
                        }
                        if (showHint) {
                            item(key = "hint") { ConversationHint(EXAMPLES, onExample = controller::submitText) }
                        }
                    },
                )
            }
        }
    }

    if (voicePickerOpen) {
        VoicePickerSheet(
            settings = settings,
            catalog = catalog,
            speaking = speaking,
            onEngineChange = setEngine,
            onDeepgramVoice = { id -> controller.updateSettings { it.copy(deepgramVoice = id) } },
            onAndroidVoice = { name -> controller.updateSettings { it.copy(androidVoiceName = name) } },
            onPreview = preview,
            onStopPreview = stopPreview,
            onDismiss = { voicePickerOpen = false },
        )
    }
}

/**
 * Orb size for the space between the bars; null means the compact one-row hero (keyboard open,
 * landscape, or very large text), which leaves the conversation room to breathe.
 */
@Composable
private fun heroOrbSize(available: Dp): Dp? {
    // Bigger text makes the caption and buttons taller, so demand more room before expanding.
    val textGrowth = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
    return when {
        available >= 660.dp + 160.dp * textGrowth -> 184.dp
        available >= 500.dp + 140.dp * textGrowth -> 136.dp
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeyTopBar(phase: AssistantPhase, onOpenSettings: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wakey", maxLines = 1)
                Spacer(Modifier.width(12.dp))
                PhaseChip(phase, Modifier.weight(1f, fill = false))
            }
        },
        actions = {
            IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun QuickControlsCard(
    settings: WakeySettings,
    wakeRunning: Boolean,
    onWakeListeningChange: (Boolean) -> Unit,
    voiceLabel: String,
    speaking: Boolean,
    onEngineChange: (TtsEngine) -> Unit,
    onOpenVoicePicker: () -> Unit,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(
                title = "Listen for “${settings.wakePhrase}”",
                checked = wakeRunning,
                onCheckedChange = onWakeListeningChange,
            )
            NoteText(PRIVACY_LINE, icon = Icons.Rounded.Lock)
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            QuickVoiceControls(
                settings = settings,
                voiceLabel = voiceLabel,
                speaking = speaking,
                onEngineChange = onEngineChange,
                onOpenPicker = onOpenVoicePicker,
                onPreview = onPreview,
                onStopPreview = onStopPreview,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/**
 * Header cards, then the conversation. The current task's action card sits right after the
 * request that started it, and the list follows the newest entry, step and timing row.
 * [headerCount] is the number of items [header] adds, so the scroll target is known up front.
 */
@Composable
private fun Feed(
    state: AssistantUiState,
    headerCount: Int,
    header: LazyListScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val entries = state.entries
    val showActions = state.currentAction != null || state.recentActions.isNotEmpty()
    val actionsAfter = entries.indexOfLast { it.speaker == Speaker.User }
    val itemCount = headerCount + entries.size + if (showActions) 1 else 0
    val last = entries.lastOrNull()

    LaunchedEffect(itemCount, last?.id, last?.timings != null, state.currentAction) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(itemCount - 1)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header()
        if (showActions && actionsAfter < 0) {
            item(key = "actions") { ActionCard(state.currentAction, state.recentActions) }
        }
        entries.forEachIndexed { index, entry ->
            item(key = entry.id) { ChatEntryRow(entry) }
            if (showActions && index == actionsAfter) {
                item(key = "actions") { ActionCard(state.currentAction, state.recentActions) }
            }
        }
    }
}
