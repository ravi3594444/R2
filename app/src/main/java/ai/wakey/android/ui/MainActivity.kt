package ai.wakey.android.ui

import ai.wakey.android.WakeyApp
import ai.wakey.android.core.AssistantController
import ai.wakey.android.ui.components.ConfirmationDialog
import ai.wakey.android.ui.components.PermissionNoticeDialog
import ai.wakey.android.ui.theme.WakeyTheme
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Wakey is dark-only, so system bar icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val controller = WakeyApp.graph.controller
        setContent {
            WakeyTheme { WakeyRoot(controller) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Wakey is visible now, so a microphone service may start even if Android had stopped it.
        WakeyApp.graph.controller.restoreWakeListening(this)
    }

}

private enum class Screen { Main, Settings, Setup }

@Composable
private fun WakeyRoot(controller: AssistantController) {
    // The mic level changes tens of times a second. It is collected on its own and read only while
    // drawing the orb, so the rest of the UI recomposes only when something else changes.
    val initialState = remember(controller) { controller.state.value.copy(micLevel = 0f) }
    val stateFlow = remember(controller) { controller.state.map { it.copy(micLevel = 0f) }.distinctUntilChanged() }
    val state by stateFlow.collectAsStateWithLifecycle(initialState)
    val micLevelFlow = remember(controller) { controller.state.map { it.micLevel }.distinctUntilChanged() }
    val micLevel = micLevelFlow.collectAsStateWithLifecycle(0f)
    val settings by controller.settings.collectAsStateWithLifecycle()
    val setup = rememberWakeySetup()
    var screen by rememberSaveable { mutableStateOf(Screen.Main) }
    // Keeps each screen's scroll position and drafts while the other one is shown.
    val saveableState = rememberSaveableStateHolder()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!settings.onboardingDone) {
            OnboardingScreen(setup, onFinish = { controller.updateSettings { it.copy(onboardingDone = true) } })
        } else {
            BackHandler(enabled = screen != Screen.Main) { screen = Screen.Main }
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    if (targetState != Screen.Main) {
                        (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut()
                    } else {
                        fadeIn() togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                    }
                },
                label = "screen",
            ) { target ->
                when (target) {
                    Screen.Main -> saveableState.SaveableStateProvider(Screen.Main.name) {
                        WakeyScreen(
                            controller = controller,
                            state = state,
                            micLevel = { micLevel.value },
                            settings = settings,
                            setup = setup,
                            onOpenSettings = { screen = Screen.Settings },
                            onOpenSetup = { screen = Screen.Setup },
                        )
                    }
                    Screen.Settings, Screen.Setup -> saveableState.SaveableStateProvider(Screen.Settings.name) {
                        SettingsScreen(
                            controller = controller,
                            state = state,
                            settings = settings,
                            setup = setup,
                            scrollToSetup = target == Screen.Setup,
                            onBack = { screen = Screen.Main },
                        )
                    }
                }
            }
        }
    }

    state.pendingConfirmation?.let { confirmation ->
        ConfirmationDialog(confirmation, voiceAnswerPossible = state.wakeServiceRunning, onAnswer = controller::answerConfirmation)
    }
    PermissionNoticeDialog(setup)
}
