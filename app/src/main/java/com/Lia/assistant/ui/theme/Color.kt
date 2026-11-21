package com.Lia.assistant.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class NovaColorScheme(
    val background: Color,
    val backgroundGradientTop: Color,
    val surface: Color,
    val surfaceGlass: Color,
    val surfaceBorder: Color,
    val surfaceRaised: Color,
    val accent: Color,
    val accentSecondary: Color,
    val accentGlow: Color,
    val onAccent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val userBubble: Color,
    val assistantBubble: Color,
    val depthShadow: Color,
    val rimLight: Color,
    val lagoon: Color,
    val lotus: Color
)

val DarkNovaColors = NovaColorScheme(
    background = Color(0xFF070C16),
    backgroundGradientTop = Color(0xFF172541),
    surface = Color(0xFF111D31),
    surfaceGlass = Color(0xCC111D31),
    surfaceBorder = Color(0x556C9CCB),
    surfaceRaised = Color(0xFF1A2B46),
    accent = Color(0xFF73D7FF),
    accentSecondary = Color(0xFF9B8CFF),
    accentGlow = Color(0x5573D7FF),
    onAccent = Color(0xFF07111D),
    textPrimary = Color(0xFFF0F6FF),
    textSecondary = Color(0xFFA7B8CE),
    textTertiary = Color(0xFF71849D),
    success = Color(0xFF3FE0D0),
    warning = Color(0xFFFFC36B),
    error = Color(0xFFFF5252),
    userBubble = Color(0xFF183A55),
    assistantBubble = Color(0xFF111D31),
    depthShadow = Color(0x80000000),
    rimLight = Color(0x26FFFFFF),
    lagoon = Color(0xFF62E5D0),
    lotus = Color(0xFFB49AFF)
)

val LightNovaColors = NovaColorScheme(
    background = Color(0xFFF3F7FC),
    backgroundGradientTop = Color(0xFFE1F0FA),
    surface = Color(0xFFFFFFFF),
    surfaceGlass = Color(0xCCFFFFFF),
    surfaceBorder = Color(0x334A7192),
    surfaceRaised = Color(0xFFFBF9FF),
    accent = Color(0xFF087EA8),
    accentSecondary = Color(0xFF6554C0),
    accentGlow = Color(0x33087EA8),
    onAccent = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF102033),
    textSecondary = Color(0xFF4E6177),
    textTertiary = Color(0xFF788BA0),
    success = Color(0xFF00897B),
    warning = Color(0xFF9A6200),
    error = Color(0xFFD32F2F),
    userBubble = Color(0xFFE2F3FC),
    assistantBubble = Color(0xFFFFFFFF),
    depthShadow = Color(0x1F000000),
    rimLight = Color(0x40FFFFFF),
    lagoon = Color(0xFF008DA8),
    lotus = Color(0xFF7257C7)
)

val LocalNovaColors = staticCompositionLocalOf { DarkNovaColors }
val LocalNovaReducedMotion = staticCompositionLocalOf { false }
