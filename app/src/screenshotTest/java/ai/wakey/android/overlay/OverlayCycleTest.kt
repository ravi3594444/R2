package ai.wakey.android.overlay

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The floating button's window: shown, animated out and detached, then shown again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayCycleTest {
    private val context = RuntimeEnvironment.getApplication()
    private val windowManager = context.getSystemService(WindowManager::class.java)

    @Test
    fun aHiddenWindowComesBack() {
        val visible = MutableTransitionState(false)
        var compositions = 0
        lateinit var window: OverlayWindow
        window = OverlayWindow(context, windowManager, OverlayWindow.params(title = "test")) {
            AnimatedVisibility(visibleState = visible, enter = fadeIn(), exit = fadeOut()) {
                SideEffect { compositions++ }
                Box(Modifier.size(40.dp).background(Color.Red))
            }
            // As OverlayManager does: detach once the exit animation is over.
            LaunchedEffect(visible.currentState, visible.isIdle) {
                if (!visible.targetState && visible.isIdle) {
                    Handler(Looper.getMainLooper()).post { if (!visible.targetState && visible.isIdle) window.hide() }
                }
            }
        }

        window.show()
        visible.targetState = true
        idle()
        assertTrue("shown", window.isShown)
        assertTrue("entered", visible.currentState)
        val first = compositions
        assertTrue(first > 0)

        visible.targetState = false
        idle()
        assertFalse("detached after the exit animation", window.isShown)

        window.show()
        visible.targetState = true
        idle()
        assertTrue("shown again", window.isShown)
        assertTrue("entered again", visible.currentState)
        assertTrue("content composed again", compositions > first)
        assertEquals(true, visible.isIdle)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
}
