package lobos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B6BCB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF0A477F),
    secondary = Color(0xFF5F6773),
    onSecondary = Color.White,
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111318),
    surfaceVariant = Color(0xFFEFF1F5),
    onSurfaceVariant = Color(0xFF5F6773),
    outline = Color(0xFFDADDE3),
    outlineVariant = Color(0xFFE8EAEF),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF111318),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0A1530),
    primaryContainer = Color(0xFF1B2B4A),
    onPrimaryContainer = Color(0xFFC6DBFF),
    secondary = Color(0xFFA8B0BD),
    onSecondary = Color(0xFF111318),
    surface = Color(0xFF141619),
    onSurface = Color(0xFFF2F4F7),
    surfaceVariant = Color(0xFF1E2126),
    onSurfaceVariant = Color(0xFFA0A8B4),
    outline = Color(0xFF303640),
    outlineVariant = Color(0xFF262B33),
    background = Color(0xFF0D0F12),
    onBackground = Color(0xFFF2F4F7),
)

private val Mono = FontFamily.Monospace

private val LobosTypography = Typography(
    displayLarge = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp, lineHeight = 44.sp),
    displayMedium = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp, lineHeight = 40.sp),
    displaySmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Normal, lineHeight = 36.sp),
    headlineLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    titleLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    titleMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

val MonoTextStyle: TextStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal)

data class LobosPalette(
    val success: Color,
    val brandMark: Color,
    val terminalBg: Color,
    val terminalBorder: Color,
    val terminalDivider: Color,
    val terminalText: Color,
    val terminalMuted: Color,
    val terminalPrompt: Color,
)

private val LightPalette = LobosPalette(
    success = Color(0xFF1B7F4B),
    brandMark = Color(0xFFFFFFFF),
    terminalBg = Color(0xFF141821),
    terminalBorder = Color(0xFF2A303C),
    terminalDivider = Color(0xFF242A35),
    terminalText = Color(0xFFE6EAF2),
    terminalMuted = Color(0xFF8B93A1),
    terminalPrompt = Color(0xFF6AA9FF),
)

private val DarkPalette = LobosPalette(
    success = Color(0xFF5EE08A),
    brandMark = Color(0xFFF2F4F7),
    terminalBg = Color(0xFF0E1013),
    terminalBorder = Color(0xFF2A303C),
    terminalDivider = Color(0xFF242A35),
    terminalText = Color(0xFFE6EAF2),
    terminalMuted = Color(0xFF8B93A1),
    terminalPrompt = Color(0xFF6AA9FF),
)

val LocalLobosPalette = staticCompositionLocalOf { LightPalette }

object LobosColors {
    val current: LobosPalette
        @Composable @ReadOnlyComposable get() = LocalLobosPalette.current
    val success: Color
        @Composable @ReadOnlyComposable get() = current.success
}

@Composable
fun LobosTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val palette = if (darkTheme) DarkPalette else LightPalette
    androidx.compose.runtime.CompositionLocalProvider(LocalLobosPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = LobosTypography, content = content)
    }
}
