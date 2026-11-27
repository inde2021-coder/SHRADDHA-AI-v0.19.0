package com.Lia.assistant.ui.theme

import androidx.compose.foundation.border
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalBottomBarInset = compositionLocalOf { 80.dp }

fun Modifier.nightSky(): Modifier = this.drawBehind {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF090D16),
                Color(0xFF111827),
                Color(0xFF090D16)
            )
        )
    )
    // Soft lavender light blooms echo the Shraddha AI interface without
    // adding noisy stars or reducing contrast behind screen content.
    drawCircle(
        color = Color(0x247B61D1),
        radius = size.minDimension * 0.42f,
        center = Offset(size.width * 0.98f, size.height * 0.04f)
    )
    drawCircle(
        color = Color(0x1878D6E8),
        radius = size.minDimension * 0.34f,
        center = Offset(size.width * 0.02f, size.height * 0.62f)
    )
}

fun Modifier.depthSurface(
    shape: Shape,
    elevation: Dp = 4.dp,
    rimColor: Color = Color(0x26FFFFFF)
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        ambientColor = Color(0x99030A14),
        spotColor = Color(0x80213B5A)
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colors = listOf(
                rimColor,
                rimColor.copy(alpha = 0.05f)
            )
        ),
        shape = shape
    )
