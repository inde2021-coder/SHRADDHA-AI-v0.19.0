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

private val NeonBlue = Color(0xFF36D8FF)
private val NeonPurple = Color(0xFF9A55FF)
private val NeonMint = Color(0xFF38E7C2)
private val GlassPanel = Color(0xCC0C1B32)

@Composable
fun HomeScreen(navController: NavController, appState: NovaAppState, modifier: Modifier = Modifier) {
    val colors = LocalNovaColors.current
    val orbState by VoiceSessionManager.state.collectAsState()
    val amplitude by VoiceSessionManager.amplitude.collectAsState()
    val assistantName = appState.assistantName.ifBlank { "Shraddha AI" }

    Column(
        modifier = modifier.fillMaxSize().nightSky().verticalScroll(rememberScrollState())
            .padding(horizontal = 15.dp).padding(bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(43.dp).clip(RoundedCornerShape(15.dp))
                    .background(Brush.linearGradient(listOf(NeonBlue, NeonPurple))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SmartToy, null, tint = Color(0xFF07111D), modifier = Modifier.size(25.dp))
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(assistantName, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("YOUR SMART AI ASSISTANT", color = NeonBlue, fontSize = 8.sp, letterSpacing = 1.45.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            GlassIcon(Icons.Default.Settings, "Settings") { navController.navigate("settings") }
            Spacer(Modifier.width(8.dp))
            GlassIcon(Icons.Default.Person, "Profile") { navController.navigate("profile") }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF102D50), Color(0xFF24184B))))
                .border(1.dp, Color(0x6674DFFF), RoundedCornerShape(18.dp)).padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("नमस्ते! मैं $assistantName हूँ", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("आपके आदेश की प्रतीक्षा में हूँ", color = Color(0xFFBBD8F5), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Box(Modifier.clip(CircleShape).background(Color(0x223FE7C5)).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text("● AI READY", color = NeonMint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().height(270.dp).clip(RoundedCornerShape(26.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF071B39), Color(0xFF10112D), Color(0xFF07101F))))
                .border(1.dp, Color(0xAA35BFFF), RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.shraddha_ai_avatar),
                contentDescription = "$assistantName holographic avatar",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 15.dp, bottom = 12.dp)
                    .size(94.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF42DFFF), Color(0xFF2544C8), Color(0xCC32106C))))
                    .border(2.dp, Color(0xFF8CEBFF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                LiaOrb3D(state = orbState, size = 88.dp, amplitude = amplitude, assistantName = assistantName)
            }
            Column(
                Modifier.align(Alignment.TopStart).padding(14.dp)
                    .clip(RoundedCornerShape(13.dp)).background(Color(0xB9081730))
                    .border(1.dp, Color(0x6676DFFF), RoundedCornerShape(13.dp)).padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text("SHRADDHA CORE", color = NeonBlue, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("Voice • Vision • Memory", color = Color.White, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.clip(CircleShape).background(Color(0xCC122943))
                .border(1.dp, Color(0x6676DFFF), CircleShape).padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(Icons.Default.GraphicEq, null, tint = NeonMint, modifier = Modifier.size(14.dp))
            Text(
                when (orbState.name) {
                    "LISTENING" -> "MIC ACTIVE • LISTENING"
                    "THINKING" -> "THINKING"
                    "SPEAKING" -> "SPEAKING"
                    else -> "READY WHEN YOU ARE"
                },
                color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeActionButton("🎙", "बोलकर बताएं", Modifier.weight(1f)) { navController.navigate("voice") }
            HomeActionButton("✉", "AI से चैट", Modifier.weight(1f)) { navController.navigate("chat") }
        }

        SectionHeading("AI FEATURES", "आपके सभी टूल एक जगह")
        FeatureRow(
            FeatureInfo("🎙", "Voice Control", "बोलकर आदेश दें", NeonBlue) { navController.navigate("voice") },
            FeatureInfo("📞", "Calls by Voice", "कॉन्टैक्ट को कॉल", NeonMint) { navController.navigate("voice") }
        )
        FeatureRow(
            FeatureInfo("💬", "WhatsApp & SMS", "मैसेज बोलकर भेजें", NeonPurple) { navController.navigate("voice") },
            FeatureInfo("📷", "Visual Lens", "लाइव कैमरा से पूछें", Color(0xFFFFB84D)) { navController.navigate("visual_lens") }
        )
        FeatureRow(
            FeatureInfo("🖥", "Read Screen / PC", "स्क्रीन और PC कंट्रोल", NeonBlue) { navController.navigate("quick_actions") },
            FeatureInfo("📁", "File Manager", "फाइल खोजें व खोलें", NeonMint) { navController.navigate("quick_actions") }
        )
        FeatureRow(
            FeatureInfo("🌐", "Web Search", "ताज़ा जानकारी", Color(0xFFFFB84D)) { navController.navigate("chat") },
            FeatureInfo("⌨", "Code Assistant", "कोड में मदद", NeonPurple) { navController.navigate("forge") }
        )
        FeatureRow(
            FeatureInfo("⚙", "Task Automation", "काम ऑटोमेट करें", NeonMint) { navController.navigate("quick_actions") },
            FeatureInfo("🧠", "Memory & Personality", "यादें और व्यवहार", NeonPurple) { navController.navigate("personality") }
        )

        SectionHeading("PERSONALIZE", "अपना AI अनुभव चुनें")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(GlassPanel).border(1.dp, Color(0x5576CFFF), RoundedCornerShape(20.dp))
                .clickable { navController.navigate("orb_style") }.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🎨", fontSize = 25.sp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("AI Styles & Themes", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Classic  •  Energy  •  Neon  •  Aura", color = Color(0xFFB8C8E3), fontSize = 10.sp)
            }
            Text("›", color = NeonBlue, fontSize = 27.sp)
        }
        Spacer(Modifier.height(9.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(GlassPanel).border(1.dp, Color(0x5576CFFF), RoundedCornerShape(20.dp))
                .clickable { navController.navigate("profile") }.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("👤", fontSize = 25.sp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Agent Name", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(assistantName, color = NeonBlue, fontSize = 11.sp)
            }
            Text("✎", color = NeonBlue, fontSize = 20.sp)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniFeature("🧠", "Memory", Modifier.weight(1f)) { navController.navigate("personality") }
            MiniFeature("🤖", "Multiple AI", Modifier.weight(1f)) { navController.navigate("settings") }
            MiniFeature("📱", "Phone ↔ PC", Modifier.weight(1f)) { navController.navigate("quick_actions") }
        }
        Spacer(Modifier.height(14.dp))
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
            .background(Brush.horizontalGradient(listOf(Color(0xFF087CB8), Color(0xFF5533A9))))
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
