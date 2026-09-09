package za.co.dielys.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The redesign's fully-rounded pill for buttons and the new-list/add rows. */
val PillShape = RoundedCornerShape(percent = 50)

/**
 * The dark blue chrome the launcher icon already uses, so the app and its icon
 * are recognisably the same thing.
 *
 * Deliberately not `dynamicColorScheme`: Material You would repaint the app from
 * the phone's wallpaper, and the two phones in this household would then look
 * like two different apps.
 */
private val Navy = Color(0xFF1B2A4A)
private val NavyLight = Color(0xFF2E4372)
private val NavyDeep = Color(0xFF0E1728)
private val Sand = Color(0xFFF6F4EF)
private val Amber = Color(0xFFE8A33D)
private val Rust = Color(0xFFB3261E)

private val LightColors =
    lightColorScheme(
        primary = Navy,
        onPrimary = Color.White,
        primaryContainer = NavyLight,
        onPrimaryContainer = Color.White,
        secondary = Amber,
        onSecondary = NavyDeep,
        background = Sand,
        onBackground = NavyDeep,
        surface = Color.White,
        onSurface = NavyDeep,
        error = Rust,
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF9CB6E8),
        onPrimary = NavyDeep,
        primaryContainer = NavyLight,
        onPrimaryContainer = Color.White,
        secondary = Amber,
        onSecondary = NavyDeep,
        background = NavyDeep,
        onBackground = Sand,
        surface = Navy,
        onSurface = Sand,
        error = Color(0xFFF2B8B5),
    )

@Composable
fun DielysTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
