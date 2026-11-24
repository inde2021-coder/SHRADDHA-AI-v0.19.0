package com.Lia.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun LiaReferenceTheme(content: @Composable () -> Unit) {
    val colors = LiaReferenceColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = true
            insets.isAppearanceLightNavigationBars = true
        }
    }
    CompositionLocalProvider(LocalNovaColors provides colors) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = colors.accent,
                onPrimary = colors.onAccent,
                primaryContainer = colors.surfaceRaised,
                onPrimaryContainer = colors.textPrimary,
                secondary = colors.accentSecondary,
                background = colors.background,
                onBackground = colors.textPrimary,
                surface = colors.surface,
                onSurface = colors.textPrimary,
                error = colors.error
            ),
            typography = NovaTypography,
            content = content
        )
    }
}

@Composable
fun NovaTheme(
    mode: String = "DARK",
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = when (mode.uppercase()) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }

    val novaColors = if (isDark) DarkNovaColors else LightNovaColors

    val materialColors = if (isDark) {
        darkColorScheme(
            primary = novaColors.accent,
            onPrimary = novaColors.onAccent,
            primaryContainer = novaColors.surfaceRaised,
            onPrimaryContainer = novaColors.textPrimary,
            secondary = novaColors.accentSecondary,
            background = novaColors.background,
            onBackground = novaColors.textPrimary,
            surface = novaColors.surface,
            onSurface = novaColors.textPrimary,
            error = novaColors.error
        )
    } else {
        lightColorScheme(
            primary = novaColors.accent,
            onPrimary = novaColors.onAccent,
            primaryContainer = novaColors.surfaceRaised,
            onPrimaryContainer = novaColors.textPrimary,
            secondary = novaColors.accentSecondary,
            background = novaColors.background,
            onBackground = novaColors.textPrimary,
            surface = novaColors.surface,
            onSurface = novaColors.textPrimary,
            error = novaColors.error
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.decorView.setBackgroundColor(novaColors.background.toArgb())
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(
        LocalNovaColors provides novaColors,
        LocalNovaReducedMotion provides reducedMotion
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = NovaTypography,
            content = content
        )
    }
}
