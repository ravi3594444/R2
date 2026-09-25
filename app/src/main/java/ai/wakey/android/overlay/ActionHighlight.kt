package ai.wakey.android.overlay

import ai.wakey.android.accessibility.NodeBounds
import ai.wakey.android.ui.theme.WakeyColors
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** One highlight of the element Wakey is acting on, in screen coordinates. */
internal data class HighlightFlash(val id: Long, val bounds: NodeBounds)

/** How long one highlight takes to appear, hold and fade. */
internal const val HIGHLIGHT_MS = 850

/**
 * A soft outline that settles onto the element Wakey taps, types into or scrolls, then fades, so
 * the user can follow each step. Drawn in a full-screen window that ignores touches; [origin] is
 * that window's position on screen.
 */
@Composable
internal fun ActionHighlight(flash: HighlightFlash?, origin: () -> IntOffset) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(flash?.id) {
        if (flash == null) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(HIGHLIGHT_MS, easing = LinearEasing))
    }
    Canvas(Modifier.fillMaxSize()) {
        val shown = flash ?: return@Canvas
        val t = progress.value
        if (t >= 1f) return@Canvas
        val appear = FastOutSlowInEasing.transform((t / APPEAR_SHARE).coerceAtMost(1f))
        val alpha = appear * if (t < FADE_FROM) 1f else 1f - (t - FADE_FROM) / (1f - FADE_FROM)
        // Settles from slightly larger onto the element.
        val grow = 1f + 0.18f * (1f - appear)
        val bounds = shown.bounds
        val o = origin()
        val pad = 4.dp.toPx()
        val width = bounds.width * grow + 2 * pad
        val height = bounds.height * grow + 2 * pad
        val topLeft = Offset(bounds.centerX - o.x - width / 2, bounds.centerY - o.y - height / 2)
        val corner = CornerRadius(minOf(14.dp.toPx(), height / 2))
        // A white ring on a dark edge, so it shows over light and dark apps alike.
        drawRoundRect(WakeyColors.White.copy(alpha = 0.14f * alpha), topLeft, Size(width, height), corner)
        drawRoundRect(WakeyColors.Black.copy(alpha = 0.55f * alpha), topLeft, Size(width, height), corner, style = Stroke(5.dp.toPx()))
        drawRoundRect(WakeyColors.White.copy(alpha = 0.95f * alpha), topLeft, Size(width, height), corner, style = Stroke(2.5.dp.toPx()))
    }
}

private const val APPEAR_SHARE = 0.22f
private const val FADE_FROM = 0.6f
