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

/** Brand and status colours that Material's scheme has no slot for. Wakey is dark-only. */
object WakeyColors {
    val Navy = Color(0xFF0E1116)
    val Periwinkle = Color(0xFF7C9CFF)
    val PeriwinkleDeep = Color(0xFF4E68D8)
    val Lilac = Color(0xFFB59CFF)
    val Teal = Color(0xFF5ED3C3)
    val Amber = Color(0xFFFFC46B)
    val Success = Color(0xFF5FD39A)
    val Failure = Color(0xFFFF7A7A)

    /** Stop button fill; white text on it meets 4.5:1. */
    val Stop = Color(0xFFD33B41)
    val OnStop = Color.White

    fun phase(phase: AssistantPhase): Color = when (phase) {
        AssistantPhase.Idle -> Color(0xFF8A92A6)
        AssistantPhase.WakeListening -> Periwinkle
        AssistantPhase.Hearing -> Teal
        AssistantPhase.Thinking -> Lilac
        AssistantPhase.Acting -> Amber
        AssistantPhase.Speaking -> Periwinkle
    }
}

private val DarkScheme = darkColorScheme(
    primary = WakeyColors.Periwinkle,
    onPrimary = Color(0xFF0A1433),
    primaryContainer = Color(0xFF26325C),
    onPrimaryContainer = Color(0xFFDDE3FF),
    inversePrimary = Color(0xFF3A55C0),
    secondary = Color(0xFFBAC3E8),
    onSecondary = Color(0xFF1F2742),
    secondaryContainer = Color(0xFF2A3150),
    onSecondaryContainer = Color(0xFFDCE1F8),
    tertiary = WakeyColors.Teal,
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF184A43),
    onTertiaryContainer = Color(0xFFBDF3E9),
    background = WakeyColors.Navy,
    onBackground = Color(0xFFE5E8F0),
    surface = WakeyColors.Navy,
    onSurface = Color(0xFFE5E8F0),
    surfaceVariant = Color(0xFF242A39),
    onSurfaceVariant = Color(0xFFABB2C4),
    surfaceTint = WakeyColors.Periwinkle,
    inverseSurface = Color(0xFFE5E8F0),
    inverseOnSurface = Color(0xFF1B1F28),
    error = WakeyColors.Failure,
    onError = Color(0xFF45090D),
    errorContainer = Color(0xFF4D1D22),
    onErrorContainer = Color(0xFFFFDAD8),
    outline = Color(0xFF4A5165),
    outlineVariant = Color(0xFF2D3343),
    scrim = Color.Black,
    surfaceBright = Color(0xFF30364A),
    surfaceDim = WakeyColors.Navy,
    surfaceContainerLowest = Color(0xFF0A0C11),
    surfaceContainerLow = Color(0xFF141821),
    surfaceContainer = Color(0xFF181D28),
    surfaceContainerHigh = Color(0xFF1F2432),
    surfaceContainerHighest = Color(0xFF272D3C),
)

private val WakeyTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val WakeyShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun WakeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = WakeyTypography, shapes = WakeyShapes, content = content)
}
