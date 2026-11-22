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
    background = Color(0xFF07131B),
    backgroundGradientTop = Color(0xFF12323D),
    surface = Color(0xFF102A35),
    surfaceGlass = Color(0xCC102A35),
    surfaceBorder = Color(0x5566C2C8),
    surfaceRaised = Color(0xFF173B47),
    accent = Color(0xFF68E7EA),
    accentSecondary = Color(0xFF62D7C8),
    accentGlow = Color(0x5568E7EA),
    onAccent = Color(0xFF07111D),
    textPrimary = Color(0xFFF0F6FF),
    textSecondary = Color(0xFFA7B8CE),
    textTertiary = Color(0xFF71849D),
    success = Color(0xFF3FE0D0),
    warning = Color(0xFFFFC36B),
    error = Color(0xFFFF5252),
    userBubble = Color(0xFF164A55),
    assistantBubble = Color(0xFF111D31),
    depthShadow = Color(0x80000000),
    rimLight = Color(0x26FFFFFF),
    lagoon = Color(0xFF62E5D0),
    lotus = Color(0xFF8CE8E7)
)

val LightNovaColors = NovaColorScheme(
    background = Color(0xFFF3F7FC),
    backgroundGradientTop = Color(0xFFE1F0FA),
    surface = Color(0xFFFFFFFF),
    surfaceGlass = Color(0xCCFFFFFF),
    surfaceBorder = Color(0x334A7192),
    surfaceRaised = Color(0xFFF2FBFA),
    accent = Color(0xFF087EA8),
    accentSecondary = Color(0xFF087F83),
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
    lotus = Color(0xFF0A9CA0)
)

val LocalNovaColors = staticCompositionLocalOf { DarkNovaColors }
val LocalNovaReducedMotion = staticCompositionLocalOf { false }
