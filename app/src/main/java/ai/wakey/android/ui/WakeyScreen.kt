package ai.wakey.android.ui

import ai.wakey.android.config.TtsEngine
import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AssistantController
import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.core.Speaker
import ai.wakey.android.tasks.TaskBoard
import ai.wakey.android.ui.components.AssistantHero
import ai.wakey.android.ui.components.ChatEntryRow
import ai.wakey.android.ui.components.ConversationHint
import ai.wakey.android.ui.components.InputBar
import ai.wakey.android.ui.components.NoteText
import ai.wakey.android.ui.components.PRIVACY_LINE
import ai.wakey.android.ui.components.QuickVoiceControls
import ai.wakey.android.ui.components.SetupNeededCard
import ai.wakey.android.ui.components.StatusBanner
import ai.wakey.android.ui.components.StepTimeline
import ai.wakey.android.ui.components.SwitchRow
import ai.wakey.android.ui.components.TasksCard
import ai.wakey.android.ui.components.VoicePickerSheet
import ai.wakey.android.ui.components.rememberVoiceCatalog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.foundation.lazy.LazyListState
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
    "Remind me to stretch in 10 minutes",
    "Wake me up at 7 am",
    "Open Chrome and search for cats",
)

/** The dashboard: orb and talk controls on top, then setup, quick controls, tasks and the conversation. */
@Composable
fun WakeyScreen(
    controller: AssistantController,
    state: AssistantUiState,
    tasks: TaskBoard,
    micLevel: () -> Float,
    settings: WakeySettings,
    setup: WakeySetup,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val context = LocalContext.current
    var voicePickerOpen by rememberSaveable { mutableStateOf(false) }
    val catalog = rememberVoiceCatalog(controller, refreshKey = voicePickerOpen)
    val speaking = state.phase == AssistantPhase.Speaking
    val setEngine = { engine: TtsEngine -> controller.updateSettings { it.copy(ttsEngine = engine) } }
    val preview = { controller.previewVoice() }
    val stopPreview = { controller.stop(silent = true) }
    val actions = DashboardActions(
        talk = { setup.withMicrophone { controller.onMicTap() } },
        stop = { controller.stop() },
        submitText = controller::submitText,
        dismissStatus = controller::dismissStatus,
        setWakeListening = { enabled ->
            if (enabled) setup.withMicrophone(forWakeWord = true) { controller.setWakeListening(context, true) }
            else controller.setWakeListening(context, false)
        },
        setFloatingButton = { enabled ->
            if (enabled) {
                // The button talks through the voice service, which needs the microphone.
                setup.withMicrophone(forWakeWord = true) {
                    controller.setFloatingButton(context, true)
                    if (!setup.status.screenControl) setup.openAccessibilitySettings()
                }
            } else {
                controller.setFloatingButton(context, false)
            }
        },
        cancelTask = controller::cancelTask,
        runTaskNow = controller::runTaskNow,
        clearFinishedTasks = controller::clearFinishedTasks,
        allowExactAlarms = setup::openExactAlarmSettings,
        setEngine = setEngine,
        openVoicePicker = { voicePickerOpen = true },
        preview = preview,
        stopPreview = stopPreview,
        allowMicrophone = { setup.withMicrophone {} },
        enableScreenControl = setup::openAccessibilitySettings,
        openSettings = onOpenSettings,
        openSetup = onOpenSetup,
    )
    WakeyDashboard(state, tasks, micLevel, settings, setup.status, catalog.currentLabel(settings), actions)

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

/** Everything the dashboard can ask for; [WakeyScreen] binds these to the controller and setup. */
internal class DashboardActions(
    val talk: () -> Unit,
    val stop: () -> Unit,
    val submitText: (String) -> Unit,
    val dismissStatus: () -> Unit,
    val setWakeListening: (Boolean) -> Unit,
    val setFloatingButton: (Boolean) -> Unit,
    val cancelTask: (Long) -> Unit,
    val runTaskNow: (Long) -> Unit,
    val clearFinishedTasks: () -> Unit,
    val allowExactAlarms: () -> Unit,
    val setEngine: (TtsEngine) -> Unit,
    val openVoicePicker: () -> Unit,
    val preview: () -> Unit,
    val stopPreview: () -> Unit,
    val allowMicrophone: () -> Unit,
    val enableScreenControl: () -> Unit,
    val openSettings: () -> Unit,
    val openSetup: () -> Unit,
)

/** The dashboard itself, from plain state, so it can also be rendered without a controller. */
@Composable
internal fun WakeyDashboard(
    state: AssistantUiState,
    tasks: TaskBoard,
    micLevel: () -> Float,
    settings: WakeySettings,
    setupStatus: SetupStatus,
    voiceLabel: String,
    actions: DashboardActions,
) {
    var setupReminderHidden by rememberSaveable { mutableStateOf(false) }
    val speaking = state.phase == AssistantPhase.Speaking
    Scaffold(
        topBar = { WakeyTopBar(actions.openSettings) },
        bottomBar = { InputBar(onSend = actions.submitText) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            val orbSize = heroOrbSize(maxHeight, hasConversation = state.entries.isNotEmpty())
            Column(Modifier.fillMaxSize()) {
                StatusBanner(state.statusMessage, state.statusIsError, onDismiss = actions.dismissStatus)
                AssistantHero(
                    state = state,
                    wakePhrase = settings.spokenWake,
                    micLevel = micLevel,
                    orbSize = orbSize,
                    onOrbTap = actions.talk,
                    onStop = actions.stop,
                )
                val showSetupReminder = setupStatus.needsAttention && !setupReminderHidden
                val showHint = state.entries.isEmpty()
                val showTasks = tasks.pendingCount > 0 || tasks.recent.isNotEmpty()
                Feed(
                    state = state,
                    modifier = Modifier.weight(1f),
                    headerCount = 1 + listOf(showSetupReminder, showTasks, showHint).count { it },
                    header = {
                        if (showSetupReminder) {
                            item(key = "setup") {
                                SetupNeededCard(
                                    status = setupStatus,
                                    onAllowMicrophone = actions.allowMicrophone,
                                    onEnableScreenControl = actions.enableScreenControl,
                                    onOpenSetup = actions.openSetup,
                                    onHide = { setupReminderHidden = true },
                                )
                            }
                        }
                        item(key = "controls") {
                            QuickControlsCard(
                                settings = settings,
                                wakeWordOn = state.wakeWordEnabled,
                                onWakeListeningChange = actions.setWakeListening,
                                screenControl = setupStatus.screenControl,
                                onFloatingButtonChange = actions.setFloatingButton,
                                voiceLabel = voiceLabel,
                                speaking = speaking,
                                onEngineChange = actions.setEngine,
                                onOpenVoicePicker = actions.openVoicePicker,
                                onPreview = actions.preview,
                                onStopPreview = actions.stopPreview,
                            )
                        }
                        if (showTasks) {
                            item(key = "tasks") {
                                TasksCard(
                                    board = tasks,
                                    exactAlarms = setupStatus.exactAlarms,
                                    onCancel = actions.cancelTask,
                                    onRunNow = actions.runTaskNow,
                                    onClearRecent = actions.clearFinishedTasks,
                                    onAllowExactAlarms = actions.allowExactAlarms,
                                )
                            }
                        }
                        if (showHint) {
                            item(key = "hint") { ConversationHint(EXAMPLES, onExample = actions.submitText) }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Orb size for the space between the bars; null means the compact one-row hero (keyboard open,
 * landscape, or very large text), which leaves the conversation room to breathe. Once there is a
 * conversation the orb steps down a size, so the messages get most of the screen.
 */
@Composable
private fun heroOrbSize(available: Dp, hasConversation: Boolean): Dp? {
    // Bigger text makes the caption and buttons taller, so demand more room before expanding.
    val textGrowth = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)
    if (hasConversation) return if (available >= 700.dp + 160.dp * textGrowth) 120.dp else null
    return when {
        available >= 660.dp + 160.dp * textGrowth -> 200.dp
        available >= 500.dp + 140.dp * textGrowth -> 144.dp
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeyTopBar(onOpenSettings: () -> Unit) {
    TopAppBar(
        title = { Wordmark() },
        actions = {
            IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** "● wakey": a white dot, like the lit orb, and the name. */
@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.onBackground, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text("Wakey", style = MaterialTheme.typography.titleLarge, maxLines = 1)
    }
}

@Composable
private fun QuickControlsCard(
    settings: WakeySettings,
    wakeWordOn: Boolean,
    onWakeListeningChange: (Boolean) -> Unit,
    screenControl: Boolean,
    onFloatingButtonChange: (Boolean) -> Unit,
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
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(
                title = "Listen for “${settings.spokenWake}”",
                checked = wakeWordOn,
                onCheckedChange = onWakeListeningChange,
            )
            SwitchRow(
                title = "Floating Wakey button",
                subtitle = floatingButtonNote(settings.floatingButton, screenControl),
                checked = settings.floatingButton,
                onCheckedChange = onFloatingButtonChange,
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

internal fun floatingButtonNote(enabled: Boolean, screenControl: Boolean): String = when {
    enabled && !screenControl -> "Turn on Wakey screen control to show it."
    else -> "Tap it in any app to talk, no wake word needed. Hold it to see your tasks."
}

/**
 * Header cards, then the conversation. A task's steps sit right after the request that started it,
 * and the list follows new entries, steps and timing rows. [headerCount] is the number of items
 * [header] adds, so the scroll target is known up front.
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
    val steps = state.recentActions
    val showSteps = state.currentAction != null || steps.isNotEmpty()
    val stepsAfter = entries.indexOfFirst { it.id == state.taskEntryId }.takeIf { it >= 0 }
        ?: entries.indexOfLast { it.speaker == Speaker.User }
    val itemCount = headerCount + entries.size + if (showSteps) 1 else 0
    val last = entries.lastOrNull()
    val running = state.taskRunning

    // Only structural changes scroll: a new entry, step or timing row, or a step finishing.
    val finishedSteps = steps.count { it.result != null }
    LaunchedEffect(itemCount, last?.id, last?.timings != null, steps.size, finishedSteps) {
        if (entries.isNotEmpty()) listState.revealEnd()
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        header()
        if (showSteps && stepsAfter < 0) {
            item(key = "steps") { StepTimeline(steps, running) }
        }
        entries.forEachIndexed { index, entry ->
            item(key = entry.id) { ChatEntryRow(entry) }
            if (showSteps && index == stepsAfter) {
                item(key = "steps") { StepTimeline(steps, running) }
            }
        }
    }
}

/** Smoothly scrolls so the end of the last item shows, even when it is taller than the screen. */
private suspend fun LazyListState.revealEnd() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == lastIndex }) animateScrollToItem(lastIndex)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastIndex } ?: return
    val overflow = item.offset + item.size - (layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding)
    if (overflow > 0) animateScrollBy(overflow.toFloat())
}
