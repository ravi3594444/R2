package ai.wakey.android.ui.theme

import ai.wakey.android.core.AssistantPhase
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Wakey is black and white only: a true-black background (pixels off on OLED screens), white
 * type and controls, and greys for everything in between. States are told apart by shape, icon
 * and motion rather than colour.
 */
object WakeyColors {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)

    /** Secondary text; 8:1 on black. */
    val Muted = Color(0xFFA3A3A3)

    /** Hairlines and card borders. */
    val Line = Color(0xFF262626)

    /** A finished step or a check that passed. */
    val Success = White

    /** A failed step; told apart from success by its icon. */
    val Failure = Color(0xFFBDBDBD)

    /** Something still to do in setup. */
    val Attention = Color(0xFFD4D4D4)

    /** Stop is the one filled white button while Wakey is busy. */
    val Stop = White
    val OnStop = Black

    /** Orb and chip tone per phase: dim grey at rest, white while Wakey is doing something. */
    fun phase(phase: AssistantPhase): Color = when (phase) {
        AssistantPhase.Idle -> Color(0xFF737373)
        AssistantPhase.WakeListening -> Color(0xFFE5E5E5)
        AssistantPhase.Hearing, AssistantPhase.Thinking, AssistantPhase.Acting, AssistantPhase.Speaking -> White
    }
}

private val MonoScheme = darkColorScheme(
    primary = WakeyColors.White,
    onPrimary = WakeyColors.Black,
    primaryContainer = Color(0xFF1F1F1F),
    onPrimaryContainer = WakeyColors.White,
    inversePrimary = WakeyColors.Black,
    secondary = Color(0xFFD4D4D4),
    onSecondary = WakeyColors.Black,
    secondaryContainer = Color(0xFF1A1A1A),
    onSecondaryContainer = Color(0xFFF5F5F5),
    tertiary = Color(0xFFE5E5E5),
    onTertiary = WakeyColors.Black,
    tertiaryContainer = Color(0xFF1A1A1A),
    onTertiaryContainer = WakeyColors.White,
    background = WakeyColors.Black,
    onBackground = WakeyColors.White,
    surface = WakeyColors.Black,
    onSurface = WakeyColors.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = WakeyColors.Muted,
    // No tint: elevated surfaces stay neutral grey.
    surfaceTint = WakeyColors.Black,
    inverseSurface = WakeyColors.White,
    inverseOnSurface = WakeyColors.Black,
    error = Color(0xFFE5E5E5),
    onError = WakeyColors.Black,
    errorContainer = Color(0xFF1A1A1A),
    onErrorContainer = WakeyColors.White,
    outline = Color(0xFF474747),
    outlineVariant = WakeyColors.Line,
    scrim = WakeyColors.Black,
    surfaceBright = Color(0xFF2A2A2A),
    surfaceDim = WakeyColors.Black,
    surfaceContainerLowest = WakeyColors.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF222222),
)

private val WakeyTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

private val WakeyShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
)

@Composable
fun WakeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MonoScheme, typography = WakeyTypography, shapes = WakeyShapes, content = content)
}
