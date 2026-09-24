package ai.wakey.android.ui

import ai.wakey.android.config.WakeySettings
import ai.wakey.android.core.AssistantController
import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.ui.components.SectionCard
import ai.wakey.android.ui.components.SetupChecklist
import ai.wakey.android.ui.components.StatusBanner
import ai.wakey.android.ui.settings.ApiKeysSection
import ai.wakey.android.ui.settings.DiagnosticsSection
import ai.wakey.android.ui.settings.FloatingButtonSection
import ai.wakey.android.ui.settings.LanguageSection
import ai.wakey.android.ui.settings.ModelSection
import ai.wakey.android.ui.settings.VoiceSection
import ai.wakey.android.ui.settings.WakeWordSection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/** Settings in display order; the order also gives the "All setup steps" link its scroll target. */
private enum class SettingsSection { Status, WakeWord, FloatingButton, Voice, Language, Model, ApiKeys, Setup, Diagnostics }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    controller: AssistantController,
    state: AssistantUiState,
    settings: WakeySettings,
    setup: WakeySetup,
    scrollToSetup: Boolean,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToSetup) {
        if (scrollToSetup) listState.scrollToItem(SettingsSection.Setup.ordinal)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            state = listState,
            // The Scaffold padding already covers the navigation bar, so only the rest of the keyboard is added.
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(SettingsSection.entries, key = { it.name }) { section ->
                when (section) {
                    SettingsSection.Status ->
                        StatusBanner(state.statusMessage, state.statusIsError, onDismiss = controller::dismissStatus)

                    SettingsSection.WakeWord -> WakeWordSection(controller, settings)
                    SettingsSection.FloatingButton -> FloatingButtonSection(controller, settings, setup)
                    SettingsSection.Voice -> VoiceSection(controller, settings, speaking = state.phase == AssistantPhase.Speaking)
                    SettingsSection.Language ->
                        LanguageSection(settings.languageMode, onChange = { mode -> controller.updateSettings { it.copy(languageMode = mode) } })

                    SettingsSection.Model -> ModelSection(controller, settings)
                    SettingsSection.ApiKeys -> ApiKeysSection(controller)
                    SettingsSection.Setup -> SectionCard(
                        "Permissions & setup",
                        icon = Icons.Rounded.VerifiedUser,
                        subtitle = "Checked again whenever you come back to Wakey.",
                    ) { SetupChecklist(setup) }

                    SettingsSection.Diagnostics -> DiagnosticsSection(state, onClearConversation = controller::clearConversation)
                }
            }
        }
    }
}
