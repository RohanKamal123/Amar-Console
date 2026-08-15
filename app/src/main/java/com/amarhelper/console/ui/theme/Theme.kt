package com.amarhelper.console.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.amarhelper.console.data.config.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Azure400,
    onPrimary = Ink900,
    primaryContainer = Azure700,
    onPrimaryContainer = Azure300,
    secondary = Mist300,
    onSecondary = Ink900,
    background = Ink900,
    onBackground = Mist100,
    surface = Ink800,
    onSurface = Mist100,
    surfaceVariant = Ink700,
    onSurfaceVariant = Mist300,
    outline = Ink600,
    outlineVariant = Ink700,
    error = Rose400,
    onError = Ink900,
    errorContainer = Rose900,
    onErrorContainer = Rose400,
)

private val LightColors = lightColorScheme(
    primary = Azure600,
    onPrimary = Paper0,
    primaryContainer = Azure100,
    onPrimaryContainer = Azure600,
    secondary = Slate600,
    onSecondary = Paper0,
    background = Paper0,
    onBackground = Graphite900,
    surface = Paper100,
    onSurface = Graphite900,
    surfaceVariant = Paper200,
    onSurfaceVariant = Graphite600,
    outline = Paper200,
    outlineVariant = Paper200,
    error = Rose700,
    onError = Paper0,
    errorContainer = Rose100,
    onErrorContainer = Rose700,
)

@Composable
fun AmarConsoleTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colors = if (dark) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}
