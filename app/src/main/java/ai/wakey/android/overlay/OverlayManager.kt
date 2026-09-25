package ai.wakey.android.overlay

import ai.wakey.android.WakeyApp
import ai.wakey.android.accessibility.NodeBounds
import ai.wakey.android.core.AssistantUiState
import ai.wakey.android.tasks.TaskBoard
import ai.wakey.android.ui.MainActivity
import ai.wakey.android.ui.components.rememberTickingNow
import ai.wakey.android.ui.theme.WakeyTheme
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Wakey's on-screen presence over other apps, owned by the accessibility service: the status pill,
 * the floating button, its task panel and the highlight on the element being acted on. All four
 * are drawn from the controller's state through [OverlayModels], so they move through a request
 * together and animate in and out instead of popping.
 *
 * [hiddenWhile] lifts them for screenshots and for gestures that would land on them. Public methods
 * may be called from any thread; window work runs on the main thread.
 */
internal class OverlayManager(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val controller get() = WakeyApp.graph.controller

    private val locked = MutableStateFlow(keyguard?.isKeyguardLocked == true)
    private val panelOpen = MutableStateFlow(false)

    /** When a touch outside the panel closed it; a touch on the button closes it this way too. */
    private var panelClosedAt = 0L

    /** The current touch on the button began with the panel open, so it only closes the panel. */
    private var touchStartedWithPanel = false
    private val refresh = MutableStateFlow(0)
    private var expiry: Job? = null
    private var hiders = 0

    // Read by the Compose content of the windows.
    private val pillModel = mutableStateOf<PillModel?>(null)
    private val pillVisible = MutableTransitionState(false)
    private val pillWidthDp = mutableFloatStateOf(0f)
    private val bubbleModel = mutableStateOf<BubbleModel?>(null)
    private val bubbleVisible = MutableTransitionState(false)
    private val bubblePressed = mutableStateOf(false)
    private val bubbleDragging = mutableStateOf(false)
    private val panelVisible = MutableTransitionState(false)
    private val panelOnRight = mutableStateOf(true)
    private val latestState = mutableStateOf(AssistantUiState())
    private val latestBoard = mutableStateOf(TaskBoard())
    private val flash = mutableStateOf<HighlightFlash?>(null)
    private val micLevel = mutableFloatStateOf(0f)
    private var flashCount = 0L

    private var bubbleOnRight = prefs.getBoolean(KEY_RIGHT, true)
    private var bubbleYFraction = prefs.getFloat(KEY_Y, DEFAULT_Y_FRACTION)
    private var snap: ValueAnimator? = null

    private val pill = OverlayWindow(
        context, windowManager,
        OverlayWindow.params(gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL, title = "Wakey status"),
    ) { WakeyTheme { PillContent() } }

    private val bubble = OverlayWindow(
        context, windowManager, OverlayWindow.params(title = "Wakey button"),
        wrap = { FloatingButtonTouchLayer(context, BubbleTouches()) },
    ) { WakeyTheme { BubbleContent() } }

    private val panel = OverlayWindow(
        context, windowManager,
        OverlayWindow.params(extraFlags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, title = "Wakey tasks"),
        wrap = {
            OutsideTouchLayer(context) {
                panelClosedAt = SystemClock.uptimeMillis()
                panelOpen.value = false
            }
        },
    ) { WakeyTheme { PanelContent() } }

    private val highlight = OverlayWindow(
        context, windowManager,
        OverlayWindow.params(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            extraFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            title = "Wakey highlight",
        ),
    ) { HighlightContent() }

    private val windows = listOf(pill, bubble, panel, highlight)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            locked.value = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> true
                Intent.ACTION_USER_PRESENT -> false
                else -> keyguard?.isKeyguardLocked == true
            }
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            context,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        applyMetrics()
        val controller = controller
        scope.launch { controller.state.map { it.micLevel }.distinctUntilChanged().collect { micLevel.floatValue = it } }
        scope.launch {
            val inputs = combine(
                controller.state.map { it.copy(micLevel = 0f) }.distinctUntilChanged(),
                controller.tasks,
                controller.settings.map { it.floatingButton }.distinctUntilChanged(),
                WakeyApp.visible,
                locked,
            ) { state, board, button, visible, isLocked -> Inputs(state, board, button, visible, isLocked) }
            combine(inputs, panelOpen, refresh) { current, open, _ -> current to open }.collect { (current, open) -> render(current, open) }
        }
    }

    fun stop() {
        scope.cancel()
        runCatching { context.unregisterReceiver(screenReceiver) }
        snap?.cancel()
        windows.forEach { it.hide() }
    }

    /** Screen size or orientation changed: resize the pill and keep the button on its edge. */
    fun onConfigurationChanged() {
        main.post(::applyMetrics)
    }

    private fun applyMetrics() {
        val metrics = screen()
        pillWidthDp.floatValue = ((metrics.width - metrics.insetsLeft - metrics.insetsRight) / density - 2 * PILL_MARGIN.value - 8f)
            .coerceAtMost(PILL_MAX_WIDTH_DP)
        pill.params.y = metrics.insetsTop
        pill.update()
        placeBubble()
    }

    /** True if an overlay covers any part of [area], so a gesture there would hit it. */
    fun covers(area: NodeBounds): Boolean = pill.covers(area) || bubble.covers(area) || panel.covers(area)

    /** Runs [block] with every overlay invisible and untouchable, after a moment for the screen to update. */
    suspend fun <T> hiddenWhile(block: suspend () -> T): T {
        val anyShown = withContext(NonCancellable + Dispatchers.Main.immediate) {
            hiders++
            applyHidden()
        }
        try {
            if (anyShown) delay(HIDE_SETTLE_MS)
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                hiders--
                applyHidden()
            }
        }
    }

    /** Briefly outlines [bounds] (screen coordinates), the element Wakey is acting on. */
    fun highlight(bounds: NodeBounds) {
        if (bounds.isEmpty) return
        main.post {
            if (locked.value) return@post
            val shown = HighlightFlash(++flashCount, bounds)
            flash.value = shown
            highlight.show()
            main.postDelayed({ if (flash.value === shown) highlight.hide() }, HIGHLIGHT_MS + HIGHLIGHT_LINGER_MS)
        }
    }

    // ------------------------------------------------------------------ rendering

    private data class Inputs(
        val state: AssistantUiState,
        val board: TaskBoard,
        val floatingButton: Boolean,
        val wakeyVisible: Boolean,
        val locked: Boolean,
    )

    private fun render(current: Inputs, open: Boolean) {
        latestState.value = current.state
        latestBoard.value = current.board
        val inputs = OverlayInputs(current.state, current.board, current.floatingButton, current.wakeyVisible, current.locked, System.currentTimeMillis())

        val pillNext = OverlayModels.pill(inputs)
        if (pillNext != null) {
            pillModel.value = pillNext
            pill.show()
            pillVisible.targetState = true
        } else {
            pillVisible.targetState = false
        }
        scheduleRefresh(OverlayModels.pillExpiresAt(inputs))

        val bubbleNext = OverlayModels.bubble(inputs)
        if (bubbleNext != null) {
            bubbleModel.value = bubbleNext
            bubble.show()
            bubbleVisible.targetState = true
        } else {
            bubbleVisible.targetState = false
        }

        if (open && bubbleNext != null) {
            placePanel()
            panel.show()
            panelVisible.targetState = true
        } else {
            panelVisible.targetState = false
            if (open) panelOpen.value = false
        }
    }

    /** Re-renders when a lingering reply should leave the pill. */
    private fun scheduleRefresh(at: Long?) {
        expiry?.cancel()
        if (at == null) return
        expiry = scope.launch {
            delay((at - System.currentTimeMillis()).coerceAtLeast(0) + 50)
            refresh.value++
        }
    }

    /** Detaches [window] once its exit animation is over, unless it was asked back meanwhile. */
    private fun detachWhenGone(window: OverlayWindow, visible: MutableTransitionState<Boolean>) {
        main.post { if (!visible.targetState && visible.isIdle) window.hide() }
    }

    private fun applyHidden(): Boolean {
        val hidden = hiders > 0
        windows.forEach { it.setHidden(hidden) }
        return windows.any { it.isShown }
    }

    // ------------------------------------------------------------------ window contents

    @Composable
    private fun PillContent() {
        val model = pillModel.value ?: return
        AnimatedVisibility(
            visibleState = pillVisible,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 3 } + scaleIn(tween(260), initialScale = 0.92f),
            exit = fadeOut(tween(200)) + slideOutVertically(tween(220)) { -it / 3 } + scaleOut(tween(220), targetScale = 0.92f),
        ) {
            StatusPill(
                model = model,
                level = { micLevel.floatValue },
                width = pillWidthDp.floatValue.dp,
                onStop = { controller.stop() },
                onAnswer = { id, approved -> controller.answerConfirmation(id, approved) },
            )
        }
        LaunchedEffect(pillVisible.currentState, pillVisible.isIdle) {
            if (!pillVisible.targetState && pillVisible.isIdle) detachWhenGone(pill, pillVisible)
        }
    }

    @Composable
    private fun BubbleContent() {
        val model = bubbleModel.value ?: return
        AnimatedVisibility(
            visibleState = bubbleVisible,
            enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.4f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.4f),
        ) {
            FloatingButtonContent(model, level = { micLevel.floatValue }, pressed = bubblePressed.value, dragging = bubbleDragging.value)
        }
        LaunchedEffect(bubbleVisible.currentState, bubbleVisible.isIdle) {
            if (!bubbleVisible.targetState && bubbleVisible.isIdle) detachWhenGone(bubble, bubbleVisible)
        }
    }

    @Composable
    private fun PanelContent() {
        val now by rememberTickingNow()
        val state = latestState.value
        val origin = TransformOrigin(if (panelOnRight.value) 1f else 0f, 0f)
        AnimatedVisibility(
            visibleState = panelVisible,
            enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.85f, transformOrigin = origin),
            exit = fadeOut(tween(160)) + scaleOut(tween(180), targetScale = 0.85f, transformOrigin = origin),
        ) {
            val metrics = screen()
            TasksPanel(
                board = latestBoard.value,
                phase = state.phase,
                currentAction = state.currentAction,
                now = now,
                width = PANEL_WIDTH_DP.dp,
                maxHeight = (metrics.height * PANEL_MAX_HEIGHT_SHARE / density).dp,
                onCancelTask = { controller.cancelTask(it) },
                onRunNow = { controller.runTaskNow(it) },
                onStop = { controller.stop() },
                onTalk = {
                    panelOpen.value = false
                    controller.onFloatingButtonTap()
                },
                onOpenApp = {
                    panelOpen.value = false
                    openWakey()
                },
                onClose = { panelOpen.value = false },
            )
        }
        LaunchedEffect(panelVisible.currentState, panelVisible.isIdle) {
            if (!panelVisible.targetState && panelVisible.isIdle) detachWhenGone(panel, panelVisible)
        }
    }

    @Composable
    private fun HighlightContent() {
        ActionHighlight(flash.value) { highlight.bounds?.let { IntOffset(it.left, it.top) } ?: IntOffset.Zero }
    }

    // ------------------------------------------------------------------ the button's position

    private inner class BubbleTouches : FloatingButtonTouchLayer.Listener {
        override fun onPressedChange(pressed: Boolean) {
            bubblePressed.value = pressed
            // The panel sees this touch as outside and may already have closed itself.
            if (pressed) touchStartedWithPanel = panelOpen.value || SystemClock.uptimeMillis() - panelClosedAt < SAME_TOUCH_MS
        }

        override fun onTap() {
            // With the panel open, a tap on the button just closes it.
            if (touchStartedWithPanel) panelOpen.value = false else controller.onFloatingButtonTap()
        }

        override fun onLongPress() {
            panelOpen.value = !touchStartedWithPanel
        }

        override fun onDrag(dx: Int, dy: Int) {
            snap?.cancel()
            bubbleDragging.value = true
            if (panelOpen.value) panelOpen.value = false
            val bounds = bubbleArea()
            bubble.params.x = (bubble.params.x + dx).coerceIn(bounds.left, bounds.right)
            bubble.params.y = (bubble.params.y + dy).coerceIn(bounds.top, bounds.bottom)
            bubble.update()
        }

        override fun onDragEnd() {
            bubbleDragging.value = false
            val bounds = bubbleArea()
            val size = bubbleSizePx()
            bubbleOnRight = bubble.params.x + size / 2 > screen().width / 2
            bubbleYFraction = if (bounds.bottom > bounds.top) (bubble.params.y - bounds.top).toFloat() / (bounds.bottom - bounds.top) else 0.5f
            prefs.edit().putBoolean(KEY_RIGHT, bubbleOnRight).putFloat(KEY_Y, bubbleYFraction).apply()
            animateBubbleTo(if (bubbleOnRight) bounds.right else bounds.left)
        }
    }

    private fun placeBubble() {
        val bounds = bubbleArea()
        snap?.cancel()
        bubble.params.x = if (bubbleOnRight) bounds.right else bounds.left
        bubble.params.y = (bounds.top + (bounds.bottom - bounds.top) * bubbleYFraction).roundToInt()
        bubble.update()
    }

    private fun animateBubbleTo(x: Int) {
        snap?.cancel()
        snap = ValueAnimator.ofInt(bubble.params.x, x).apply {
            duration = SNAP_MS
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                bubble.params.x = it.animatedValue as Int
                bubble.update()
            }
            start()
        }
    }

    /** Where the button's top-left corner may go: inside the screen, clear of the system bars. */
    private fun bubbleArea(): NodeBounds {
        val metrics = screen()
        val size = bubbleSizePx()
        val margin = (EDGE_MARGIN_DP * density).roundToInt()
        return NodeBounds(
            left = metrics.insetsLeft + margin - size / 8,
            top = metrics.insetsTop + margin,
            right = metrics.width - metrics.insetsRight - size - margin + size / 8,
            bottom = metrics.height - metrics.insetsBottom - size - margin,
        )
    }

    private fun placePanel() {
        val metrics = screen()
        val size = bubbleSizePx()
        val panelWidth = ((PANEL_WIDTH_DP + 16) * density).roundToInt()
        val right = bubble.params.x + size / 2 > metrics.width / 2
        panelOnRight.value = right
        panel.params.x = if (right) (bubble.params.x + size - panelWidth).coerceAtLeast(0) else bubble.params.x.coerceAtMost(metrics.width - panelWidth)
        val maxHeight = (metrics.height * PANEL_MAX_HEIGHT_SHARE).roundToInt()
        panel.params.y = (bubble.params.y + size).coerceAtMost(metrics.height - metrics.insetsBottom - maxHeight).coerceAtLeast(metrics.insetsTop)
        panel.update()
    }

    private fun bubbleSizePx() = (BUBBLE_WINDOW.value * density).roundToInt()

    private val density: Float get() = context.resources.displayMetrics.density

    private class Screen(val width: Int, val height: Int, val insetsLeft: Int, val insetsTop: Int, val insetsRight: Int, val insetsBottom: Int)

    private fun screen(): Screen = try {
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        Screen(metrics.bounds.width(), metrics.bounds.height(), insets.left, insets.top, insets.right, insets.bottom)
    } catch (_: RuntimeException) {
        // Some builds refuse window metrics to non-activity contexts: assume a phone's bars.
        val display = context.resources.displayMetrics
        Screen(display.widthPixels, display.heightPixels, 0, (24 * density).roundToInt(), 0, (48 * density).roundToInt())
    }

    private fun openWakey() {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val PREFS = "wakey_overlay"
        const val KEY_RIGHT = "bubble_right"
        const val KEY_Y = "bubble_y"
        const val DEFAULT_Y_FRACTION = 0.55f
        const val EDGE_MARGIN_DP = 4f
        const val SNAP_MS = 260L
        const val PILL_MAX_WIDTH_DP = 460f
        const val PANEL_WIDTH_DP = 340f
        const val PANEL_MAX_HEIGHT_SHARE = 0.6f
        const val HIDE_SETTLE_MS = 120L
        /** An outside touch closing the panel this close to a touch on the button is that same touch. */
        const val SAME_TOUCH_MS = 150L
        const val HIGHLIGHT_LINGER_MS = 150L
    }
}

/** A panel's window root that closes it when the user touches anywhere else. */
@SuppressLint("ViewConstructor")
private class OutsideTouchLayer(context: Context, private val onOutside: () -> Unit) : FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside()
            return true
        }
        return super.dispatchTouchEvent(event)
    }
}
