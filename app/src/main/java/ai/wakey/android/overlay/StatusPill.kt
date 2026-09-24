package ai.wakey.android.overlay

import ai.wakey.android.R
import ai.wakey.android.ui.theme.WakeyColors
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The pill's translucent navy, readable over any app. */
private val PillBackground = Color(0xF2171B24)

internal fun PillMode.color(): Color = when (this) {
    PillMode.Listening -> WakeyColors.Teal
    PillMode.Thinking -> WakeyColors.Lilac
    PillMode.Acting, PillMode.Confirm -> WakeyColors.Amber
    PillMode.Done -> WakeyColors.Success
    PillMode.Failed -> WakeyColors.Failure
}

private fun PillModel.label(): String = when (mode) {
    PillMode.Listening -> "Listening"
    PillMode.Thinking -> "Thinking"
    PillMode.Acting -> when {
        step == null -> "Working"
        deciding -> "Step $step done · planning the next"
        else -> "Step $step"
    }
    PillMode.Confirm -> "Needs your OK"
    PillMode.Done -> "Done"
    PillMode.Failed -> "Couldn't finish"
}

/**
 * What Wakey is doing, shown near the top of the screen while it works in another app: a glyph for
 * the phase (with the step number while acting), a label, the current words, action or reply, and
 * Stop, or Approve / Deny when a step needs the user's OK. Every part animates on its own, so a new
 * step slides in instead of the whole pill blinking.
 */
@Composable
internal fun StatusPill(
    model: PillModel,
    level: () -> Float,
    width: Dp,
    onStop: () -> Unit,
    onAnswer: (id: Long, approved: Boolean) -> Unit,
) {
    val accent by animateColorAsState(model.mode.color(), tween(ACCENT_MS), label = "pillAccent")
    Surface(
        modifier = Modifier.padding(PILL_MARGIN).width(width),
        shape = RoundedCornerShape(28.dp),
        color = PillBackground,
        contentColor = Color.White,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusGlyph(model, accent, level, Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = model.label(),
                    transitionSpec = { fadeIn(tween(TEXT_IN_MS)) togetherWith fadeOut(tween(TEXT_OUT_MS)) },
                    label = "pillLabel",
                ) { label ->
                    Text(label, color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Live transcripts change word by word: they update in place instead of animating.
                AnimatedContent(
                    targetState = model,
                    contentKey = { if (it.mode == PillMode.Listening) PillMode.Listening else it.text },
                    transitionSpec = {
                        (fadeIn(tween(TEXT_IN_MS)) + slideInVertically(tween(TEXT_IN_MS)) { it / 2 }) togetherWith
                            (fadeOut(tween(TEXT_OUT_MS)) + slideOutVertically(tween(TEXT_OUT_MS)) { -it / 2 }) using SizeTransform(clip = false)
                    },
                    label = "pillText",
                ) { shown ->
                    Text(
                        shown.text,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (model.mode == PillMode.Confirm && model.confirmationId != null) {
                val id = model.confirmationId
                Spacer(Modifier.width(8.dp))
                PillButton("Deny", Color.White.copy(alpha = 0.12f), Color.White) { onAnswer(id, false) }
                Spacer(Modifier.width(6.dp))
                PillButton("Approve", WakeyColors.Success, Color(0xFF06291A)) { onAnswer(id, true) }
            } else {
                AnimatedVisibility(
                    visible = model.canStop,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    Row {
                        Spacer(Modifier.width(8.dp))
                        PillButton(stringResource(R.string.overlay_stop), WakeyColors.Stop, WakeyColors.OnStop, onStop)
                    }
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, background: Color, content: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = background, contentColor = content) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** A tinted disc whose glyph and ring follow the mode; the step number counts up while acting. */
@Composable
private fun StatusGlyph(model: PillModel, accent: Color, level: () -> Float, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(accent.copy(alpha = 0.16f)) }
        Crossfade(targetState = model.glyphKey(), animationSpec = tween(ACCENT_MS), label = "pillRing") { key ->
            when (key) {
                GlyphKey.Listening -> VoiceRing(accent, level)
                GlyphKey.Working -> SpinningArc(accent, periodMs = 900)
                GlyphKey.Deciding, GlyphKey.Thinking -> SpinningArc(accent, periodMs = 1_400)
                GlyphKey.Confirm, GlyphKey.Done, GlyphKey.Failed -> Unit
            }
        }
        Crossfade(targetState = model.glyphKey(), animationSpec = tween(ACCENT_MS), label = "pillGlyph") { key ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (key) {
                    GlyphKey.Listening -> Icon(Icons.Rounded.Mic, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    GlyphKey.Working, GlyphKey.Deciding -> StepNumber(model.step, accent)
                    GlyphKey.Thinking -> PulsingDot(accent)
                    GlyphKey.Confirm -> Icon(Icons.Rounded.GppMaybe, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    GlyphKey.Done -> PoppedIcon(Icons.Rounded.Check, accent)
                    GlyphKey.Failed -> PoppedIcon(Icons.Rounded.Close, accent)
                }
            }
        }
    }
}

private enum class GlyphKey { Listening, Thinking, Working, Deciding, Confirm, Done, Failed }

private fun PillModel.glyphKey(): GlyphKey = when (mode) {
    PillMode.Listening -> GlyphKey.Listening
    PillMode.Thinking -> GlyphKey.Thinking
    PillMode.Acting -> if (deciding) GlyphKey.Deciding else GlyphKey.Working
    PillMode.Confirm -> GlyphKey.Confirm
    PillMode.Done -> GlyphKey.Done
    PillMode.Failed -> GlyphKey.Failed
}

@Composable
private fun StepNumber(step: Int?, color: Color) {
    if (step == null) {
        Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        return
    }
    AnimatedContent(
        targetState = step,
        transitionSpec = {
            (slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn()) togetherWith
                (slideOutVertically(spring(stiffness = Spring.StiffnessMediumLow)) { -it } + fadeOut())
        },
        label = "pillStep",
    ) { number ->
        Text("$number", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** Pops in with a small bounce when the step finishes. */
@Composable
private fun PoppedIcon(icon: ImageVector, color: Color) {
    AnimatedVisibility(
        visibleState = remember { MutableTransitionState(false).apply { targetState = true } },
        enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SpinningArc(color: Color, periodMs: Int) {
    val angle by rememberInfiniteTransition(label = "arc").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
        label = "arcAngle",
    )
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2
        rotate(angle) {
            drawArc(
                brush = Brush.sweepGradient(0f to Color.Transparent, 0.7f to color, 1f to Color.Transparent, center = center),
                startAngle = 0f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** A ring that swells with the microphone level. */
@Composable
private fun VoiceRing(color: Color, level: () -> Float) {
    val breath by rememberInfiniteTransition(label = "voice").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200), RepeatMode.Reverse),
        label = "voiceBreath",
    )
    Canvas(Modifier.fillMaxSize()) {
        val loud = level().coerceIn(0f, 1f)
        val radius = size.minDimension / 2 * (0.78f + 0.08f * breath + 0.2f * loud)
        drawCircle(color.copy(alpha = 0.25f + 0.45f * loud), radius = radius.coerceAtMost(size.minDimension / 2), style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dotPulse",
    )
    Canvas(Modifier.size(12.dp)) { drawCircle(color.copy(alpha = pulse), radius = size.minDimension / 2 * pulse) }
}

internal val PILL_MARGIN = 8.dp
private const val ACCENT_MS = 350
private const val TEXT_IN_MS = 260
private const val TEXT_OUT_MS = 140
