package ai.wakey.android.ui

import ai.wakey.android.WakeyApp
import ai.wakey.android.core.AssistantController
import ai.wakey.android.ui.components.ConfirmationDialog
import ai.wakey.android.ui.components.PermissionNoticeDialog
import ai.wakey.android.ui.theme.WakeyTheme
import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    /** Counts requests from the floating button to listen here; each new value starts listening once. */
    private val listenRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Wakey is dark-only, so system bar icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A recreated activity keeps its launch intent; only a fresh launch asks to listen.
        if (savedInstanceState == null && intent?.action == ACTION_LISTEN) listenRequests.value++
        val controller = WakeyApp.graph.controller
        setContent {
            WakeyTheme { WakeyRoot(controller, listenRequests) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Wakey is visible now, so a microphone service may start even if Android had stopped it.
        WakeyApp.graph.controller.restoreWakeListening(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_LISTEN) listenRequests.value++
    }

    companion object {
        /** From the floating button when the microphone can't be used from the background: open and listen. */
        const val ACTION_LISTEN = "ai.wakey.android.action.LISTEN"
    }
}

private enum class Screen { Main, Settings, Setup }

@Composable
private fun WakeyRoot(controller: AssistantController, listenRequests: StateFlow<Int>) {
    // The mic level changes tens of times a second. It is collected on its own and read only while
    // drawing the orb, so the rest of the UI recomposes only when something else changes.
    val initialState = remember(controller) { controller.state.value.copy(micLevel = 0f) }
    val stateFlow = remember(controller) { controller.state.map { it.copy(micLevel = 0f) }.distinctUntilChanged() }
    val state by stateFlow.collectAsStateWithLifecycle(initialState)
    val micLevelFlow = remember(controller) { controller.state.map { it.micLevel }.distinctUntilChanged() }
    val micLevel = micLevelFlow.collectAsStateWithLifecycle(0f)
    val settings by controller.settings.collectAsStateWithLifecycle()
    val tasks by controller.tasks.collectAsStateWithLifecycle()
    val setup = rememberWakeySetup()
    var screen by rememberSaveable { mutableStateOf(Screen.Main) }
    val listenRequest by listenRequests.collectAsStateWithLifecycle()
    LaunchedEffect(listenRequest) {
        if (listenRequest > 0 && settings.onboardingDone) {
            screen = Screen.Main
            setup.withMicrophone { controller.onMicTap() }
        }
    }
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
                            tasks = tasks,
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
