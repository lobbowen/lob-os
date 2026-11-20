package lobos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightScheme = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFF5FF),
    onPrimaryContainer = Color(0xFF1D4E9E),
    secondary = Color(0xFF5B6470),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF101216),
    surfaceVariant = Color(0xFFF0F2F5),
    onSurfaceVariant = Color(0xFF8B929D),
    outline = Color(0xFFE2E5EA),
    outlineVariant = Color(0xFFEDF0F4),
    background = Color(0xFFEEF1F4),
    onBackground = Color(0xFF101216),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6AA9FF),
    onPrimary = Color(0xFF0A1530),
    primaryContainer = Color(0xFF1B2B4A),
    onPrimaryContainer = Color(0xFFB8D4FF),
    secondary = Color(0xFFA0A7B1),
    surface = Color(0xFF121418),
    onSurface = Color(0xFFF2F4F7),
    surfaceVariant = Color(0xFF1C1F24),
    onSurfaceVariant = Color(0xFF9AA1AC),
    outline = Color(0xFF2A2E35),
    outlineVariant = Color(0xFF22262C),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFF2F4F7),
)

private val LobosTypography = Typography(
    displayLarge = TextStyle(fontSize = 42.sp, fontWeight = FontWeight(680), letterSpacing = (-1.8).sp, lineHeight = 46.sp),
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight(680), letterSpacing = (-1.2).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight(680), letterSpacing = (-0.9).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight(600)),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight(600)),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight(400)),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight(500)),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight(400)),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight(600)),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight(650), letterSpacing = 0.8.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight(650), letterSpacing = 1.1.sp),
)

private val LightSuccess = Color(0xFF249A55)
private val DarkSuccess = Color(0xFF5EE08A)

val LocalLobosSuccessColor = staticCompositionLocalOf { LightSuccess }

object LobosColors {
    val success: Color
        @Composable @ReadOnlyComposable get() = LocalLobosSuccessColor.current
}

@Composable
fun LobosTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    androidx.compose.runtime.CompositionLocalProvider(LocalLobosSuccessColor provides if (darkTheme) DarkSuccess else LightSuccess) {
        MaterialTheme(colorScheme = scheme, typography = LobosTypography, content = content)
    }
}
