package lobos.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme()
private val LightColors = lightColorScheme()
private val DarkSuccess = Color(0xFF6EE7A8)
private val LightSuccess = Color(0xFF1B7F4B)

val LocalLobosSuccessColor = staticCompositionLocalOf { LightSuccess }

object LobosColors {
    val success: Color
        @Composable @ReadOnlyComposable get() = LocalLobosSuccessColor.current
}

@Composable
fun LobosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicDarkColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val successColor = if (darkTheme) DarkSuccess else LightSuccess
    CompositionLocalProvider(LocalLobosSuccessColor provides successColor) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
