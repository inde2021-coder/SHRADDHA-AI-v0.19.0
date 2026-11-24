package com.Lia.assistant.ui.fx

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.LocalNovaReducedMotion
import com.Lia.assistant.voice.NovaOrbState
import kotlin.math.cos
import kotlin.math.sin

enum class NovaOrbStyle(val displayName: String) {
    NOVA("Nova Pearl"),
    AURORA("Aurora Borealis"),
    PLASMA("Plasma Core"),
    GLASS("Glass Sphere"),
    ENERGY("Energy Pulse"),
    MINIMAL("Minimal"),
    ARCHER("Golden Archer"),
    SATURN("Saturn"),
    SUN("Sun"),
    MOON("Moon"),
    MARS("Mars"),
    MERCURY("Mercury"),
    JUPITER("Jupiter"),
    VENUS("Venus");

    companion object {
        fun fromString(name: String): NovaOrbStyle {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NOVA
        }
    }
}

@Composable
fun NovaOrb(
    state: NovaOrbState,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    amplitude: Float = 0f,
    style: NovaOrbStyle = NovaOrbStyle.NOVA,
    assistantName: String = AssistantBrand.NAME
) {
    val colors = LocalNovaColors.current
    val reducedMotion = LocalNovaReducedMotion.current

    val transition = rememberInfiniteTransition(label = "OrbMotion")

    // Breathing: 4.2 s sine (+-3.5%)
    val breathProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Breath"
    )

    // Rotation: 9s default; 2.6s thinking; 1.1s connecting
    val rotDuration = when (state) {
        NovaOrbState.CONNECTING -> 1100
        NovaOrbState.THINKING -> 2600
        else -> 9000
    }

    val rotationProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(rotDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    // Palette per state
    val (coreColor, rimColor, haloColor) = when (state) {
        NovaOrbState.IDLE -> Triple(Color(0xFF273A68), colors.lotus, Color(0x557C8FFF))
        NovaOrbState.LISTENING -> Triple(Color(0xFF145C70), colors.lagoon, Color(0x5562E5D0))
        NovaOrbState.THINKING -> Triple(Color(0xFF3E347A), colors.accentSecondary, Color(0x559B8CFF))
        NovaOrbState.SPEAKING -> Triple(colors.accentSecondary, colors.accent, colors.accentGlow)
        NovaOrbState.CONNECTING -> Triple(Color(0xFF315E87), colors.accent, Color(0x5573D7FF))
        NovaOrbState.ERROR -> Triple(Color(0xFFB71C1C), colors.error, Color(0x40FF5252))
    }

    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = "$assistantName status: ${state.name.lowercase()}"
            }
    ) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)

        val breathScale = if (reducedMotion) 1f else 1f + 0.035f * sin(breathProgress * 2 * Math.PI).toFloat()
        val ampDisplacement = (amplitude.coerceIn(0f, 1f) * 0.15f)
        val currentRadius = radius * 0.70f * (breathScale + ampDisplacement)

        // 1. Radial outer halo glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(haloColor, Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )

        // 2. State-specific background rings (Listening expanding rings)
        if (state == NovaOrbState.LISTENING && !reducedMotion) {
            val ringProgress = (breathProgress * 2f) % 1f
            drawCircle(
                color = colors.lagoon.copy(alpha = (1f - ringProgress) * 0.5f),
                radius = currentRadius + (radius - currentRadius) * ringProgress,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // 3. Connecting 100-degree arc
        if (state == NovaOrbState.CONNECTING) {
            drawArc(
                color = colors.accent,
                startAngle = if (reducedMotion) 0f else rotationProgress,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(center.x - currentRadius * 1.15f, center.y - currentRadius * 1.15f),
                size = Size(currentRadius * 2.3f, currentRadius * 2.3f),
                style = Stroke(width = 3.dp.toPx())
            )
        }

        // 4. ARCHER Style golden spiral particles
        if (style == NovaOrbStyle.SATURN) {
            drawSaturnSphere(center, currentRadius, rotationProgress, amplitude, colors)
        } else if (style == NovaOrbStyle.ARCHER) {
            drawArcherSphere(center, currentRadius, rotationProgress, amplitude, colors)
        } else if (style in setOf(NovaOrbStyle.SUN, NovaOrbStyle.MOON, NovaOrbStyle.MARS, NovaOrbStyle.MERCURY, NovaOrbStyle.JUPITER, NovaOrbStyle.VENUS)) {
            drawPlanetSphere(center, currentRadius, rotationProgress, amplitude, style, reducedMotion)
        } else {
            // Radial gradient core
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(rimColor, coreColor, coreColor.copy(alpha = 0.8f)),
                    center = center - Offset(currentRadius * 0.25f, currentRadius * 0.25f),
                    radius = currentRadius
                ),
                radius = currentRadius,
                center = center
            )

            // Orbiting particles
            val particleCount = when (style) {
                NovaOrbStyle.ENERGY -> 8
                NovaOrbStyle.PLASMA -> 6
                NovaOrbStyle.GLASS -> 3
                NovaOrbStyle.MINIMAL -> 0
                else -> if (state == NovaOrbState.ERROR) 0 else 5
            }

            if (particleCount > 0 && !reducedMotion) {
                for (i in 0 until particleCount) {
                    val angleDeg = (rotationProgress + (i * 360f / particleCount)) % 360f
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val orbitDist = currentRadius * 0.85f
                    val px = center.x + (orbitDist * cos(angleRad)).toFloat()
                    val py = center.y + (orbitDist * sin(angleRad) * 0.45f).toFloat()
                    drawCircle(
                        color = Color.White.copy(alpha = 0.85f),
                        radius = 2.5.dp.toPx(),
                        center = Offset(px, py)
                    )
                }
            }

            // Glass highlight arc
            if (style == NovaOrbStyle.GLASS) {
                drawArc(
                    color = Color.White.copy(alpha = 0.65f),
                    startAngle = 200f,
                    sweepAngle = 60f,
                    useCenter = false,
                    topLeft = Offset(center.x - currentRadius * 0.85f, center.y - currentRadius * 0.85f),
                    size = Size(currentRadius * 1.7f, currentRadius * 1.7f),
                    style = Stroke(width = 3.5.dp.toPx())
                )
            }
        }

        // Error pulse ring
        if (state == NovaOrbState.ERROR) {
            drawCircle(
                color = colors.error.copy(alpha = 0.7f),
                radius = currentRadius * 1.1f,
                center = center,
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
    }
}


private fun DrawScope.drawPlanetSphere(center: Offset, radius: Float, rotation: Float, amplitude: Float, style: NovaOrbStyle, reducedMotion: Boolean) {
    val palette = when (style) {
        NovaOrbStyle.SUN -> listOf(Color(0xFFFFF6B0), Color(0xFFFFB52E), Color(0xFFE64A19))
        NovaOrbStyle.MOON -> listOf(Color(0xFFF8FAFF), Color(0xFF9CA9BB), Color(0xFF3E485A))
        NovaOrbStyle.MARS -> listOf(Color(0xFFFFB18B), Color(0xFFC14432), Color(0xFF541D25))
        NovaOrbStyle.MERCURY -> listOf(Color(0xFFE7E5DF), Color(0xFF92969C), Color(0xFF414751))
        NovaOrbStyle.JUPITER -> listOf(Color(0xFFFFE1B5), Color(0xFFC58A59), Color(0xFF684331))
        NovaOrbStyle.VENUS -> listOf(Color(0xFFFFE8D2), Color(0xFFE6A77D), Color(0xFF98604F))
        else -> listOf(Color.White, Color.Gray, Color.DarkGray)
    }
    drawCircle(brush = Brush.radialGradient(palette, center - Offset(radius * 0.24f, radius * 0.26f), radius * 1.25f), radius = radius * (0.88f + amplitude.coerceIn(0f, 1f) * 0.02f), center = center)
    when (style) {
        NovaOrbStyle.SUN -> {
            if (!reducedMotion) rotate(rotation * 0.22f, center) {
                for (i in 0 until 24) {
                    val a = Math.toRadians(i * 15.0)
                    val inner = radius * 0.98f
                    val outer = radius * (1.12f + if (i % 2 == 0) 0.10f else 0.04f)
                    drawLine(Color(0xFFFFD76A).copy(alpha = 0.85f), Offset(center.x + cos(a).toFloat() * inner, center.y + sin(a).toFloat() * inner), Offset(center.x + cos(a).toFloat() * outer, center.y + sin(a).toFloat() * outer), strokeWidth = radius * 0.035f)
                }
            }
            drawCircle(Color(0xFFFFF4B0).copy(alpha = 0.28f), radius * 0.52f, center)
        }
        NovaOrbStyle.MOON -> {
            val craters = listOf(Offset(-0.28f, -0.16f), Offset(0.24f, -0.30f), Offset(0.34f, 0.18f), Offset(-0.12f, 0.36f), Offset(-0.42f, 0.22f))
            craters.forEachIndexed { i, p ->
                drawCircle(Color(0xFF4C586A).copy(alpha = 0.22f), radius * (0.09f + (i % 2) * 0.035f), center + Offset(p.x * radius, p.y * radius))
                drawCircle(Color.White.copy(alpha = 0.16f), radius * 0.045f, center + Offset((p.x - 0.025f) * radius, (p.y - 0.025f) * radius))
            }
        }
        NovaOrbStyle.MARS -> {
            for (i in 0..5) drawArc(SolidColor(Color(0xFF5A2028).copy(alpha = 0.22f)), 190f + i * 9f, 95f, false, Offset(center.x - radius * 0.72f, center.y - radius * 0.52f + i * radius * 0.16f), Size(radius * 1.44f, radius * 0.16f), alpha = 1f, style = Stroke(radius * 0.045f))
            drawCircle(Color(0xFFFFC09A).copy(alpha = 0.25f), radius * 0.12f, center + Offset(radius * 0.28f, -radius * 0.18f))
        }
        NovaOrbStyle.MERCURY -> {
            for (i in 0..8) {
                val a = i * 2.399
                val d = radius * (0.18f + (i % 3) * 0.17f)
                drawCircle(Color(0xFF303741).copy(alpha = 0.20f), radius * (0.035f + (i % 2) * 0.025f), center + Offset(cos(a).toFloat() * d, sin(a).toFloat() * d))
            }
        }
        NovaOrbStyle.JUPITER -> {
            for (i in 0..5) drawArc(SolidColor(if (i % 2 == 0) Color(0xFF8B5239).copy(alpha = 0.48f) else Color(0xFFFFE1B5).copy(alpha = 0.40f)), 185f, 170f, false, Offset(center.x - radius * 0.78f, center.y - radius * 0.52f + i * radius * 0.17f), Size(radius * 1.56f, radius * 0.19f), alpha = 1f, style = Stroke(radius * 0.07f))
            drawOval(Color(0xFF9F4938).copy(alpha = 0.88f), Offset(center.x + radius * 0.18f, center.y + radius * 0.12f), Size(radius * 0.32f, radius * 0.16f))
        }
        NovaOrbStyle.VENUS -> {
            drawCircle(Color(0xFFFFD9B5).copy(alpha = 0.22f), radius * 0.66f, center)
            if (!reducedMotion) rotate(rotation * 0.12f, center) {
                drawArc(SolidColor(Color(0xFFFFF0D9).copy(alpha = 0.48f)), 205f, 130f, false, Offset(center.x - radius, center.y - radius * 0.38f), Size(radius * 2f, radius * 0.76f), alpha = 1f, style = Stroke(radius * 0.035f))
            }
        }
        else -> Unit
    }
}

private fun DrawScope.drawArcherSphere(
    center: Offset,
    radius: Float,
    rotation: Float,
    amplitude: Float,
    colors: com.Lia.assistant.ui.theme.NovaColorScheme
) {
    val count = 280
    val phi = (1 + kotlin.math.sqrt(5.0)) / 2.0
    val rotRad = Math.toRadians(rotation.toDouble())

    for (i in 0 until count) {
        val y = 1.0 - (i.toDouble() / (count - 1)) * 2.0 // -1 to 1
        val radiusAtY = kotlin.math.sqrt(1.0 - y * y)
        val theta = 2.0 * Math.PI * i / phi + rotRad

        val x = kotlin.math.cos(theta) * radiusAtY
        val z = kotlin.math.sin(theta) * radiusAtY

        val depthAlpha = ((z + 1.0) / 2.0).coerceIn(0.15, 1.0).toFloat()
        val particleRadius = (1.5f + (z.toFloat() * 1.0f) + (amplitude * 1.2f)).coerceAtLeast(0.8f)

        val px = center.x + (x * radius).toFloat()
        val py = center.y + (y * radius).toFloat()

        drawCircle(
            color = if (z > 0) colors.accent.copy(alpha = depthAlpha) else colors.lotus.copy(alpha = depthAlpha),
            radius = particleRadius,
            center = Offset(px, py)
        )
    }
}


private fun DrawScope.drawSaturnSphere(
    center: Offset,
    radius: Float,
    rotation: Float,
    amplitude: Float,
    colors: com.Lia.assistant.ui.theme.NovaColorScheme
) {
    val ringWidth = (radius * 0.075f).coerceAtLeast(2.2f)
    val ringSize = Size(radius * 2.18f, radius * 0.78f)
    val ringTop = Offset(center.x - ringSize.width / 2f, center.y - ringSize.height / 2f)
    rotate(degrees = -19f + rotation * 0.08f, pivot = center) {
        drawOval(
            color = Color(0xFFE7D7AA).copy(alpha = 0.78f),
            topLeft = ringTop,
            size = ringSize,
            style = Stroke(width = ringWidth)
        )
        drawArc(
            color = Color(0xFFFFF0C6).copy(alpha = 0.85f),
            startAngle = 190f,
            sweepAngle = 155f,
            useCenter = false,
            topLeft = ringTop,
            size = ringSize,
            style = Stroke(width = ringWidth * 0.48f)
        )
    }

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFF4E4BD), Color(0xFFAD8C55), Color(0xFF5E4A32), Color(0xFF211D20)),
            center = center - Offset(radius * 0.22f, radius * 0.24f),
            radius = radius * 0.88f
        ),
        radius = radius * (0.64f + amplitude.coerceIn(0f, 1f) * 0.025f),
        center = center
    )
    for (i in 0..3) {
        val y = center.y - radius * 0.35f + i * radius * 0.20f
        drawArc(
            color = Color(0xFF5C4937).copy(alpha = 0.23f),
            startAngle = 195f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(center.x - radius * 0.57f, y - radius * 0.075f),
            size = Size(radius * 1.14f, radius * 0.15f),
            style = Stroke(width = radius * 0.035f)
        )
    }
    rotate(degrees = -19f + rotation * 0.08f, pivot = center) {
        drawArc(
            color = Color(0xFFFFE7A8).copy(alpha = 0.94f),
            startAngle = 8f,
            sweepAngle = 164f,
            useCenter = false,
            topLeft = ringTop,
            size = ringSize,
            style = Stroke(width = ringWidth * 0.72f)
        )
    }
}
