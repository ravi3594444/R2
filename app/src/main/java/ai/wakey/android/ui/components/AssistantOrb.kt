package ai.wakey.android.ui.components

import ai.wakey.android.core.AgentActionInfo
import ai.wakey.android.core.AssistantPhase
import ai.wakey.android.ui.theme.WakeyColors
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.sin

/** The inner disc's share of the orb; the ring around it is room for glows, ripples and arcs. */
private const val CORE_FRACTION = 0.62f

/**
 * Wakey's orb and main mic button. Each phase has its own quiet animation:
 * idle is static, wake listening breathes, hearing ripples with the voice, thinking spins an arc,
 * acting pulses a ring around the step count, speaking shows wave bars.
 *
 * [micLevel] is read only while drawing, so level updates redraw the orb without recomposition.
 */
@Composable
fun AssistantOrb(
    phase: AssistantPhase,
    micLevel: () -> Float,
    action: AgentActionInfo?,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = 176.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "orbPress")
    val level = remember { Animatable(0f) }
    val currentMicLevel by rememberUpdatedState(micLevel)
    val hearing = phase == AssistantPhase.Hearing
    LaunchedEffect(hearing) {
        if (hearing) {
            snapshotFlow { currentMicLevel().coerceIn(0f, 1f) }
                .collectLatest { level.animateTo(it, spring(stiffness = Spring.StiffnessMediumLow)) }
        } else {
            level.animateTo(0f)
        }
    }

    val phaseColor = WakeyColors.phase(phase)
    val accent by animateColorAsState(phaseColor, tween(450), label = "orbAccent")
    val idle = phase == AssistantPhase.Idle
    val coreLight by animateColorAsState(
        if (idle) MaterialTheme.colorScheme.surfaceContainerHighest else phaseColor,
        tween(450),
        label = "coreLight",
    )
    val coreDark by animateColorAsState(
        if (idle) MaterialTheme.colorScheme.surfaceContainerHigh else lerp(phaseColor, WakeyColors.PeriwinkleDeep, 0.55f),
        tween(450),
        label = "coreDark",
    )
    val contentColor by animateColorAsState(
        if (idle) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF0A1433),
        label = "orbContent",
    )

    val tapModifier = if (onTap != null) {
        Modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = if (hearing) "finish speaking" else "talk",
                onClick = onTap,
            )
            .semantics {
                contentDescription = "Wakey microphone"
                stateDescription = phase.label
            }
    } else {
        Modifier.clearAndSetSemantics {}
    }

    Box(modifier.size(size).then(tapModifier), contentAlignment = Alignment.Center) {
        Crossfade(targetState = phase, animationSpec = tween(450), label = "orbEffect") { p ->
            OrbEffect(p, accent = WakeyColors.phase(p), level = { level.value }, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxSize(CORE_FRACTION)
                .graphicsLayer {
                    val scale = pressScale * (1f + 0.07f * level.value)
                    scaleX = scale
                    scaleY = scale
                }
                .background(Brush.linearGradient(listOf(coreLight, coreDark)), CircleShape)
                .border(1.dp, accent.copy(alpha = if (idle) 0.3f else 0f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            OrbContent(phase, action, contentColor, coreSize = size * CORE_FRACTION)
        }
    }
}

@Composable
private fun OrbEffect(phase: AssistantPhase, accent: Color, level: () -> Float, modifier: Modifier) {
    when (phase) {
        AssistantPhase.Idle -> Canvas(modifier) { drawGlow(accent, 0.07f, reach = 0.35f) }
        AssistantPhase.WakeListening -> BreathingGlow(accent, modifier)
        AssistantPhase.Hearing -> VoiceRipples(accent, level, modifier)
        AssistantPhase.Thinking -> ThinkingArc(accent, modifier)
        AssistantPhase.Acting -> ActingPulse(accent, modifier)
        AssistantPhase.Speaking -> SpeakingGlow(accent, modifier)
    }
}

private val DrawScope.orbRadius get() = size.minDimension / 2
private val DrawScope.coreRadius get() = orbRadius * CORE_FRACTION

/** A soft halo from the core edge outwards; [reach] is the share of the outer ring it covers. */
private fun DrawScope.drawGlow(color: Color, alpha: Float, reach: Float) {
    val radius = coreRadius + (orbRadius - coreRadius) * reach.coerceIn(0.05f, 1f)
    drawCircle(
        Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            coreRadius / radius to color.copy(alpha = alpha),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
    )
}

@Composable
private fun BreathingGlow(accent: Color, modifier: Modifier) {
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathValue",
    )
    Canvas(modifier) { drawGlow(accent, alpha = 0.12f + 0.2f * breath, reach = 0.45f + 0.5f * breath) }
}

@Composable
private fun VoiceRipples(accent: Color, level: () -> Float, modifier: Modifier) {
    val progress by rememberInfiniteTransition(label = "ripples").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "rippleProgress",
    )
    Canvas(modifier) {
        val loudness = level()
        drawGlow(accent, alpha = 0.1f + 0.3f * loudness, reach = 0.4f + 0.6f * loudness)
        val strokeWidth = 1.5.dp.toPx() + 3.dp.toPx() * loudness
        val maxRadius = orbRadius - strokeWidth / 2
        repeat(RIPPLES) { index ->
            val wave = (progress + index.toFloat() / RIPPLES) % 1f
            val reach = (maxRadius - coreRadius) * (0.5f + 0.5f * loudness)
            drawCircle(
                color = accent.copy(alpha = (1f - wave) * (0.2f + 0.6f * loudness)),
                radius = coreRadius + reach * wave,
                style = Stroke(strokeWidth),
            )
        }
    }
}

private const val RIPPLES = 3

@Composable
private fun ThinkingArc(accent: Color, modifier: Modifier) {
    val angle by rememberInfiniteTransition(label = "thinking").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "thinkingAngle",
    )
    Canvas(modifier) {
        val stroke = 5.dp.toPx()
        val radius = coreRadius + (orbRadius - coreRadius) * 0.45f
        drawCircle(accent.copy(alpha = 0.1f), radius = radius, style = Stroke(stroke))
        rotate(angle) {
            drawArc(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.45f to WakeyColors.Periwinkle.copy(alpha = 0.5f),
                    0.75f to accent,
                    1f to Color.Transparent,
                    center = center,
                ),
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun ActingPulse(accent: Color, modifier: Modifier) {
    val pulse by rememberInfiniteTransition(label = "acting").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing)),
        label = "actingPulse",
    )
    Canvas(modifier) {
        val ring = coreRadius + 6.dp.toPx()
        drawCircle(accent.copy(alpha = 0.6f), radius = ring, style = Stroke(3.dp.toPx()))
        val strokeWidth = 2.dp.toPx() + 3.dp.toPx() * (1f - pulse)
        val maxRadius = orbRadius - strokeWidth / 2
        drawCircle(
            accent.copy(alpha = 0.55f * (1f - pulse)),
            radius = ring + (maxRadius - ring) * pulse,
            style = Stroke(strokeWidth),
        )
    }
}

@Composable
private fun SpeakingGlow(accent: Color, modifier: Modifier) {
    val shimmer by rememberInfiniteTransition(label = "speaking").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "speakingShimmer",
    )
    Canvas(modifier) { drawGlow(accent, alpha = 0.16f + 0.1f * shimmer, reach = 0.6f + 0.15f * shimmer) }
}

@Composable
private fun OrbContent(phase: AssistantPhase, action: AgentActionInfo?, color: Color, coreSize: Dp) {
    Crossfade(targetState = phase, label = "orbContent") { p ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (p) {
                AssistantPhase.Idle, AssistantPhase.WakeListening, AssistantPhase.Hearing ->
                    Icon(Icons.Rounded.Mic, contentDescription = null, tint = color, modifier = Modifier.size(coreSize * 0.42f))

                AssistantPhase.Thinking ->
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = color, modifier = Modifier.size(coreSize * 0.38f))

                AssistantPhase.Acting ->
                    if (action != null) StepCount(action, color, coreSize)
                    else Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = color, modifier = Modifier.size(coreSize * 0.4f))

                AssistantPhase.Speaking -> WaveBars(color, Modifier.size(coreSize * 0.5f, coreSize * 0.4f))
            }
        }
    }
}

/** Sized from the orb rather than the font scale: it is part of the graphic, and the action card repeats it as text. */
@Composable
private fun StepCount(action: AgentActionInfo, color: Color, coreSize: Dp) {
    val density = LocalDensity.current
    // The small orb only has room for the step number.
    val roomy = coreSize >= 64.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (roomy) "${action.step}/${action.maxSteps}" else "${action.step}",
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = with(density) { (coreSize * if (roomy) 0.24f else 0.4f).toSp() },
            maxLines = 1,
        )
        if (roomy) {
            Text("step", color = color.copy(alpha = 0.8f), fontSize = with(density) { (coreSize * 0.11f).toSp() }, maxLines = 1)
        }
    }
}

@Composable
private fun WaveBars(color: Color, modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "waveTime",
    )
    Canvas(modifier) {
        val slot = size.width / (BAR_ENVELOPE.size * 2 - 1)
        BAR_ENVELOPE.forEachIndexed { i, envelope ->
            val swing = (sin(2 * PI * (t + i * 0.17f)).toFloat() + 1f) / 2f
            val height = size.height * envelope * (0.3f + 0.7f * swing)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * 2 * slot, (size.height - height) / 2),
                size = Size(slot, height),
                cornerRadius = CornerRadius(slot / 2),
            )
        }
    }
}

/** Taller bars in the middle, like a voice waveform. */
private val BAR_ENVELOPE = floatArrayOf(0.55f, 0.85f, 1f, 0.85f, 0.55f)
