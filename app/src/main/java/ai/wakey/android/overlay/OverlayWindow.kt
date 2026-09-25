package ai.wakey.android.overlay

import ai.wakey.android.accessibility.NodeBounds
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * One accessibility-overlay window drawn with Compose. Accessibility overlays need no
 * draw-over-apps permission and sit above other apps; Wakey's own windows are never read back by
 * the agent. [wrap] may put the Compose view inside a container that handles touches itself.
 *
 * Main thread only.
 */
internal class OverlayWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    val params: WindowManager.LayoutParams,
    private val wrap: (ComposeView) -> ViewGroup? = { null },
    private val content: @Composable () -> Unit,
) {
    private var root: View? = null
    private var owner: OverlayLifecycleOwner? = null
    private var hidden = false

    /** Windows created untouchable (the tap highlight) stay that way when shown again. */
    private val alwaysUntouchable = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0

    /** Where the window is on screen while shown. */
    @Volatile var bounds: NodeBounds? = null
        private set

    val isShown: Boolean get() = root != null

    fun show() {
        if (root != null) return
        val lifecycle = OverlayLifecycleOwner().apply { start() }
        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent(content)
        }
        val view: View = wrap(compose)?.apply { addView(compose) } ?: compose
        view.setViewTreeLifecycleOwner(lifecycle)
        view.setViewTreeSavedStateRegistryOwner(lifecycle)
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val location = IntArray(2).also(v::getLocationOnScreen)
            bounds = NodeBounds(location[0], location[1], location[0] + v.width, location[1] + v.height)
        }
        try {
            windowManager.addView(view, params)
        } catch (_: RuntimeException) {
            // The service is disconnecting; there is nothing to draw over.
            lifecycle.destroy()
            return
        }
        root = view
        owner = lifecycle
        applyHidden()
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        bounds = null
        owner?.destroy()
        owner = null
    }

    /** Applies changes made to [params] (position, flags). */
    fun update() {
        root?.let { runCatching { windowManager.updateViewLayout(it, params) } }
    }

    /** Invisible and untouchable while [value], e.g. during a screenshot or a gesture beneath it. */
    fun setHidden(value: Boolean) {
        hidden = value
        applyHidden()
    }

    fun covers(area: NodeBounds): Boolean {
        val b = bounds ?: return false
        if (!isShown || hidden) return false
        return area.left < b.right && b.left < area.right && area.top < b.bottom && b.top < area.bottom
    }

    private fun applyHidden() {
        val view = root ?: return
        view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        params.flags = if (hidden || alwaysUntouchable) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        update()
    }

    companion object {
        /** Layout params for a non-focusable accessibility overlay at [gravity]. */
        fun params(
            width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
            height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
            gravity: Int = Gravity.TOP or Gravity.START,
            extraFlags: Int = 0,
            title: String,
        ) = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or extraFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.title = title
        }
    }
}

/** Lifecycle and saved-state owner for Compose in a window that has no Activity. */
private class OverlayLifecycleOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun start() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
