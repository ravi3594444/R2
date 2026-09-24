package ai.wakey.android.overlay

import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.ui.theme.WakeyColors
import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The floating button's window is this square; the orb leaves room for its rings and badge. */
internal val BUBBLE_WINDOW = 68.dp
private val BUBBLE_CORE = 48.dp
private const val IDLE_ALPHA = 0.72f

private fun AssistantPhase.bubbleColor(): Color = when (this) {
    AssistantPhase.Idle, AssistantPhase.WakeListening -> WakeyColors.Periwinkle
    AssistantPhase.Hearing -> WakeyColors.Teal
    AssistantPhase.Thinking -> WakeyColors.Lilac
    AssistantPhase.Acting -> WakeyColors.Amber
    AssistantPhase.Speaking -> WakeyColors.Periwinkle
}

/**
 * The floating Wakey button: an orb in the phase's colour with a matching ring (ripples while
 * listening, a spinning arc while thinking, a pulse and the step number while acting), a badge with
 * the number of pending tasks, and a faded look when idle so it stays out of the way.
 */
@Composable
internal fun FloatingButtonContent(model: BubbleModel, level: () -> Float, pressed: Boolean, dragging: Boolean) {
    val color by animateColorAsState(model.phase.bubbleColor(), tween(450), label = "bubbleColor")
    val alpha by animateFloatAsState(if (model.active || pressed || dragging) 1f else IDLE_ALPHA, tween(500), label = "bubbleAlpha")
    val scale by animateFloatAsState(
        when {
            pressed && !dragging -> 0.9f
            dragging -> 1.08f
            else -> 1f
        },
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bubbleScale",
    )
    Box(
        Modifier.size(BUBBLE_WINDOW).graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
        },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = model.phase, animationSpec = tween(450), label = "bubbleRing") { phase ->
            BubbleRing(phase, color, level)
        }
        Box(
            Modifier
                .size(BUBBLE_CORE)
                .shadow(8.dp, CircleShape)
                .background(Brush.linearGradient(listOf(color, lerp(color, WakeyColors.PeriwinkleDeep, 0.55f))), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = model.phase to model.step, animationSpec = tween(300), label = "bubbleGlyph") { (phase, step) ->
                BubbleGlyph(phase, step)
            }
        }
        AnimatedVisibility(
            visible = model.pendingTasks > 0,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Box(
                Modifier.padding(top = 2.dp, end = 2.dp).sizeIn(minWidth = 20.dp, minHeight = 20.dp).background(WakeyColors.Amber, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = model.pendingTasks.coerceAtMost(99),
                    transitionSpec = { slideInVertically { it } + fadeIn() togetherWith slideOutVertically { -it } + fadeOut() },
                    label = "bubbleBadge",
                ) { count ->
                    Text("$count", color = GLYPH, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp))
                }
            }
        }
    }
}

private val GLYPH = Color(0xFF0A1433)

@Composable
private fun BubbleGlyph(phase: AssistantPhase, step: Int?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            phase == AssistantPhase.Acting && step != null ->
                Text("$step", color = GLYPH, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            phase == AssistantPhase.Acting -> Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = GLYPH, modifier = Modifier.size(22.dp))
            phase == AssistantPhase.Thinking -> Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = GLYPH, modifier = Modifier.size(22.dp))
            phase == AssistantPhase.Speaking -> Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = GLYPH, modifier = Modifier.size(22.dp))
            else -> Icon(Icons.Rounded.Mic, contentDescription = null, tint = GLYPH, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun BubbleRing(phase: AssistantPhase, color: Color, level: () -> Float) {
    val transition = rememberInfiniteTransition(label = "bubbleFx")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (phase == AssistantPhase.Thinking) 1_200 else 1_600, easing = if (phase == AssistantPhase.Acting) FastOutSlowInEasing else LinearEasing),
            if (phase == AssistantPhase.Speaking) RepeatMode.Reverse else RepeatMode.Restart,
        ),
        label = "bubbleFxProgress",
    )
    Canvas(Modifier.fillMaxSize()) {
        val core = BUBBLE_CORE.toPx() / 2
        val outer = size.minDimension / 2
        when (phase) {
            AssistantPhase.Idle, AssistantPhase.WakeListening -> Unit
            AssistantPhase.Hearing -> {
                val loud = level().coerceIn(0f, 1f)
                repeat(2) { index ->
                    val wave = (progress + index / 2f) % 1f
                    drawCircle(
                        color.copy(alpha = (1f - wave) * (0.25f + 0.55f * loud)),
                        radius = core + (outer - core) * wave * (0.55f + 0.45f * loud),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
            AssistantPhase.Thinking -> {
                val stroke = 3.dp.toPx()
                val radius = core + (outer - core) * 0.55f
                rotate(progress * 360f) {
                    drawArc(
                        brush = Brush.sweepGradient(0f to Color.Transparent, 0.75f to color, 1f to Color.Transparent, center = center),
                        startAngle = 0f,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            AssistantPhase.Acting -> drawCircle(
                color.copy(alpha = 0.55f * (1f - progress)),
                radius = core + (outer - core) * progress,
                style = Stroke(2.dp.toPx() + 2.dp.toPx() * (1f - progress)),
            )
            AssistantPhase.Speaking -> drawCircle(color.copy(alpha = 0.12f + 0.14f * progress), radius = core + (outer - core) * (0.5f + 0.3f * progress))
        }
    }
}

/**
 * The floating button's window root. It handles touches itself in screen coordinates, since the
 * window moves under the finger while dragging: a tap talks, a long press opens the task panel,
 * and a drag moves the button. TalkBack users get the same through click and long-click actions.
 */
@SuppressLint("ViewConstructor")
internal class FloatingButtonTouchLayer(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onPressedChange(pressed: Boolean)
        fun onTap()
        fun onLongPress()
        fun onDrag(dx: Int, dy: Int)
        fun onDragEnd()
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longPressed = false
    private val longPress = Runnable {
        if (!dragging) {
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener.onLongPress()
        }
    }

    init {
        isClickable = true
        isLongClickable = true
        contentDescription = "Wakey. Double-tap to talk, double-tap and hold to see tasks."
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                dragging = false
                longPressed = false
                listener.onPressedChange(true)
                postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !longPressed && hypot(event.rawX - downX, event.rawY - downY) > touchSlop) {
                    dragging = true
                    removeCallbacks(longPress)
                }
                if (dragging) {
                    listener.onDrag((event.rawX - lastX).roundToInt(), (event.rawY - lastY).roundToInt())
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                listener.onPressedChange(false)
                when {
                    dragging -> listener.onDragEnd()
                    !longPressed -> performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                listener.onPressedChange(false)
                if (dragging) listener.onDragEnd()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener.onTap()
        return true
    }

    override fun performLongClick(): Boolean {
        super.performLongClick()
        listener.onLongPress()
        return true
    }
}
