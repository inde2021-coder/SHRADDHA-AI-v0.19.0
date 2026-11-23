package com.Lia.assistant.ui.screens.home

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.VoiceSessionManager
import `in`.shraddha.ai.R

private val NeonBlue = Color(0xFFB58BFF)
private val NeonPurple = Color(0xFFDA8CFF)
private val NeonMint = Color(0xFF78E4D6)
private val GlassPanel = Color(0xCC21183A)

@Composable
fun HomeScreen(navController: NavController, appState: NovaAppState, modifier: Modifier = Modifier) {
    val colors = LocalNovaColors.current
    val orbState by VoiceSessionManager.state.collectAsState()
    val amplitude by VoiceSessionManager.amplitude.collectAsState()
    val assistantName = appState.assistantName.ifBlank { "Shraddha AI" }

    Column(
        modifier = modifier.fillMaxSize().nightSky().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(bottom = 108.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(NeonBlue, NeonPurple))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.SmartToy, null, tint = Color(0xFF171027), modifier = Modifier.size(24.dp))
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(assistantName, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("PERSONAL AI COMPANION", color = NeonBlue, fontSize = 8.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            GlassIcon(Icons.Default.Settings, "Settings") { navController.navigate("settings") }
            Spacer(Modifier.width(8.dp))
            GlassIcon(Icons.Default.Person, "Profile") { navController.navigate("profile") }
        }
        Box(
            Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(30.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF251444), Color(0xFF100D20), Color(0xFF080A14))))
                .border(1.dp, Color(0x557D52D8), RoundedCornerShape(30.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(painter = painterResource(R.drawable.shraddha_ai_avatar), contentDescription = "$assistantName avatar", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.TopCenter).padding(top = 15.dp).clip(CircleShape).background(Color(0xAA130D23)).border(1.dp, Color(0x668F62E8), CircleShape).padding(horizontal = 13.dp, vertical = 7.dp)) {
                Text("$assistantName • AI ONLINE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).size(104.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFB78BFF), Color(0xFF6B39C8), Color(0xCC210F46)))).border(2.dp, Color(0xCCDCBCFF), CircleShape), contentAlignment = Alignment.Center) {
                LiaOrb3D(state = orbState, size = 98.dp, amplitude = amplitude, assistantName = assistantName)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.clip(CircleShape).background(Color(0xCC1A1430)).border(1.dp, Color(0x557D52D8), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Default.GraphicEq, null, tint = NeonMint, modifier = Modifier.size(14.dp))
            Text(when (orbState.name) { "LISTENING" -> "LISTENING"; "THINKING" -> "THINKING"; "SPEAKING" -> "SPEAKING"; else -> "READY WHEN YOU ARE" }, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeActionButton("🎙", "Talk to AI", Modifier.weight(1f)) { navController.navigate("voice") }
            HomeActionButton("✦", "Quick actions", Modifier.weight(1f)) { navController.navigate("quick_actions") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xCC17152B)).border(1.dp, Color(0x447D52D8), RoundedCornerShape(18.dp)).clickable { navController.navigate("chat") }.padding(horizontal = 15.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ask me anything…", color = Color(0xFFB8A9D4), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("➤", color = NeonPurple, fontSize = 19.sp)
        }
        SectionHeading("YOUR SPACE", "Everything you need, one tap away")
        FeatureRow(FeatureInfo("💬", "AI Chat", "Ask anything", NeonPurple) { navController.navigate("chat") }, FeatureInfo("👁️", "Visual Lens", "Camera intelligence", NeonMint) { navController.navigate("visual_lens") })
        FeatureRow(FeatureInfo("🖥️", "PC Companion", "Connect Windows", NeonBlue) { navController.navigate("device_center") }, FeatureInfo("🧠", "Multi-agent Team", "Delegate and verify", NeonPurple) { navController.navigate("agent_council") })
        FeatureRow(FeatureInfo("🛠️", "Expert Studio", "Engineering tools", NeonMint) { navController.navigate("expert_studio") }, FeatureInfo("🔐", "Memory Vault", "Private knowledge", NeonBlue) { navController.navigate("data_vault") })
        Spacer(Modifier.height(10.dp))
    }
}

private data class FeatureInfo(val emoji: String, val title: String, val subtitle: String, val accent: Color, val action: () -> Unit)

@Composable
private fun FeatureRow(left: FeatureInfo, right: FeatureInfo) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        FeatureCard(left, Modifier.weight(1f))
        FeatureCard(right, Modifier.weight(1f))
    }
    Spacer(Modifier.height(9.dp))
}

@Composable
private fun FeatureCard(info: FeatureInfo, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(17.dp))
            .background(Brush.linearGradient(listOf(Color(0xE61B3151), Color(0xE6171838))))
            .border(1.dp, info.accent.copy(alpha = 0.62f), RoundedCornerShape(17.dp))
            .clickable(onClick = info.action).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(info.emoji, fontSize = 22.sp)
        Column(Modifier.padding(start = 8.dp)) {
            Text(info.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(info.subtitle, color = Color(0xFFB4C6E0), fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp)) {
        Text(title, color = NeonBlue, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.7.sp)
        Text(subtitle, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun GlassIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color(0xCC142944))
            .border(1.dp, Color(0x6676DFFF), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun HomeActionButton(emoji: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF5931A5), Color(0xFF8A3FAD))))
            .border(1.dp, Color(0xAA7DEBFF), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(emoji, fontSize = 18.sp)
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 7.dp))
    }
}

@Composable
private fun MiniFeature(emoji: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(15.dp)).background(Color(0xCC132641))
            .border(1.dp, Color(0x5576CFFF), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(emoji, fontSize = 16.sp)
        Text(label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 5.dp))
    }
}
