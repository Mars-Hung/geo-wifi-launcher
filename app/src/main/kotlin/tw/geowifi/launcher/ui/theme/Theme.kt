package tw.geowifi.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF14221F)
private val Paper = Color(0xFFF6F3EC)
private val Teal = Color(0xFF0E6B67)
private val TealDark = Color(0xFF8FCFC8)
private val Clay = Color(0xFFC46B3A)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Clay,
    onSecondary = Color.White,
    background = Paper,
    surface = Color.White,
    onBackground = Ink,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = Ink,
    secondary = Color(0xFFE7A27A),
    background = Color(0xFF101614),
    surface = Color(0xFF1A2421),
    onBackground = Color(0xFFF3EFE6),
    onSurface = Color(0xFFF3EFE6),
)

@Composable
fun ContextLaunchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
