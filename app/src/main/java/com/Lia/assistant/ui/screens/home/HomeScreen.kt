package com.Lia.assistant.ui.screens.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.fx.NovaOrbStyle
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.SansFontFamily
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.VoiceSessionManager
import java.util.Calendar

@Composable
fun HomeScreen(navController: NavController, appState: NovaAppState, modifier: Modifier = Modifier) {
    val colors = LocalNovaColors.current
    val orbState by VoiceSessionManager.state.collectAsState()
    val amplitude by VoiceSessionManager.amplitude.collectAsState()
    val pulse = rememberInfiniteTransition(label = "shraddha-home-pulse")
    val halo by pulse.animateFloat(
        initialValue = 0.91f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orb-halo"
    )
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Welcome back"
    }
    val assistantName = appState.assistantName.ifBlank { "Shraddha AI" }
    val orbSize = 252.dp

    Column(
        modifier = modifier.fillMaxSize().nightSky().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp).padding(bottom = 108.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF5CE1E6), Color(0xFF8275FF)))),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.SmartToy, null, tint = Color(0xFF07111D), modifier = Modifier.size(24.dp))
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(assistantName, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SansFontFamily)
                Text("PERSONAL INTELLIGENCE", color = colors.textTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF17283D).copy(alpha = 0.86f))
                .border(1.dp, Color(0x4479D9F4), CircleShape).clickable { navController.navigate("profile") }
                .semantics { contentDescription = "Open profile settings" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, "Profile", tint = colors.textPrimary, modifier = Modifier.size(20.dp))
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$greeting, \${appState.userName}", color = colors.textPrimary, fontSize = 25.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, fontFamily = SansFontFamily)
            Text("Your world, understood.", color = colors.textSecondary, fontSize = 13.sp,
                letterSpacing = 0.35.sp, modifier = Modifier.padding(top = 7.dp))
        }

        Spacer(Modifier.height(28.dp))
        Box(Modifier.size(orbSize + 44.dp).clickable { navController.navigate("voice") }
            .semantics { contentDescription = "Open $assistantName voice assistant" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val base = size.minDimension * 0.40f
                drawCircle(Brush.radialGradient(listOf(Color(0x5557DFF0), Color(0x227A72FF), Color.Transparent), center, base * 1.55f), base * 1.55f, center)
                drawCircle(Color(0x5577D9F4), base * halo, center, style = Stroke(width = 1.2.dp.toPx()))
                drawCircle(Color(0x338B80FF), base * 1.13f, center, style = Stroke(width = 1.dp.toPx()))
                drawArc(Brush.sweepGradient(listOf(Color.Transparent, Color(0xFF6BE7F2), Color(0xFF9B8BFF), Color.Transparent)),
                    -75f, 118f, false, Offset(center.x - base * 1.16f, center.y - base * 1.16f),
                    androidx.compose.ui.geometry.Size(base * 2.32f, base * 2.32f),
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
            }
            Box(Modifier.size(orbSize).graphicsLayer { scaleX = halo * 0.985f; scaleY = halo * 0.985f }, contentAlignment = Alignment.Center) {
                LiaOrb3D(state = orbState, size = orbSize, amplitude = amplitude,
                    style = NovaOrbStyle.fromString(appState.orbStyle), assistantName = assistantName)
            }
        }

        Row(Modifier.clip(CircleShape).background(Color(0xFF14283A).copy(alpha = 0.82f))
            .border(1.dp, Color(0x5572DDEB), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Default.GraphicEq, null, tint = Color(0xFF65E4DE), modifier = Modifier.size(14.dp))
            Text(when (orbState.name) {
                "LISTENING" -> "LISTENING"
                "THINKING" -> "THINKING"
                "SPEAKING" -> "SPEAKING"
                else -> "READY WHEN YOU ARE"
            }, color = colors.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.15.sp)
        }

        Spacer(Modifier.height(25.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NovaButton("Speak", { navController.navigate("voice") }, NovaButtonType.PRIMARY, icon = Icons.Default.Mic)
            NovaButton("Message", { navController.navigate("chat") }, NovaButtonType.SECONDARY)
        }

        Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xCC1A3450), Color(0xCC171F3D))))
            .border(1.dp, Color(0x4478CBEA), RoundedCornerShape(22.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF263F5B)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Widgets, null, tint = Color(0xFF83E5EA), modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Text("Explore capabilities", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Voice  ·  Vision  ·  Memory  ·  Devices", color = colors.textSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text("✦", color = Color(0xFF9B8BFF), fontSize = 22.sp)
        }
        Spacer(Modifier.height(12.dp))
    }
}
