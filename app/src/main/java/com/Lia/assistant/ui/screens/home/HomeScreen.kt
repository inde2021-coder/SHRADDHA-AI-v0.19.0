package com.Lia.assistant.ui.screens.home

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
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.NovaOrbState
import com.Lia.assistant.voice.VoiceSessionManager

private val Cyan = Color(0xFF48DFFF)
private val Violet = Color(0xFF9B7BFF)
private val Mint = Color(0xFF4CE4C4)
private val Glass = Color(0xCC101D33)

@Composable
fun HomeScreen(navController: NavController, appState: NovaAppState, modifier: Modifier = Modifier) {
    val colors = LocalNovaColors.current
    val voiceState by VoiceSessionManager.state.collectAsState()
    val amplitude by VoiceSessionManager.amplitude.collectAsState()
    val assistantName = appState.assistantName.ifBlank { "Shraddha AI" }
    val stateLabel = when (voiceState) {
        NovaOrbState.LISTENING -> "LISTENING • I'M HERE"
        NovaOrbState.THINKING -> "THINKING • ONE MOMENT"
        NovaOrbState.SPEAKING -> "SPEAKING • SHRADDHA AI"
        NovaOrbState.CONNECTING -> "CONNECTING SECURELY"
        NovaOrbState.ERROR -> "VOICE CONNECTION NEEDS ATTENTION"
        else -> "READY WHEN YOU ARE"
    }

    Column(
        modifier = modifier.fillMaxSize().nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Cyan, Violet))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.SmartToy, null, tint = Color(0xFF07111D), modifier = Modifier.size(25.dp))
            }
            Column(Modifier.padding(start = 11.dp)) {
                Text(assistantName, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("YOUR INTELLIGENT AI COMPANION", color = Cyan, fontSize = 8.sp, letterSpacing = 1.15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            HeaderIcon(Icons.Default.History, "History") { navController.navigate("history") }
            Spacer(Modifier.width(8.dp))
            HeaderIcon(Icons.Default.Settings, "Settings") { navController.navigate("settings") }
        }

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF152D4B), Color(0xFF241A49), Color(0xFF111B35))))
            .border(1.dp, Color(0x6678DFFF), RoundedCornerShape(23.dp)).padding(17.dp)) {
            Text(if (appState.userName.isNotBlank()) "नमस्ते, ${appState.userName}!" else "नमस्ते!", color = Color(0xFF9DEAFF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("मैं $assistantName हूँ", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Text("आज मैं आपकी किस तरह मदद कर सकती हूँ?", color = Color(0xFFBED0E8), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF10294A), Color(0xFF11152F), Color(0xFF090F1E))))
            .border(1.dp, Color(0x6677DFFF), RoundedCornerShape(28.dp)).padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SHRADDHA CORE", color = Cyan, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.ExtraBold)
                Text("VOICE  •  VISION  •  MEMORY", color = Color(0xFFB7C8E0), fontSize = 9.sp, letterSpacing = 1.sp)
                Box(Modifier.size(226.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0x6637DFFF), Color(0x222B5CCB), Color.Transparent)))
                    .clickable { navController.navigate("voice") }, contentAlignment = Alignment.Center) {
                    LiaOrb3D(state = voiceState, size = 204.dp, amplitude = amplitude, assistantName = assistantName)
                }
                Row(Modifier.clip(CircleShape).background(Color(0xCC132943))
                    .border(1.dp, Color(0x6678DFFF), CircleShape).padding(horizontal = 13.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Default.GraphicEq, null, tint = Mint, modifier = Modifier.size(14.dp))
                    Text(stateLabel, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                }
                Spacer(Modifier.height(13.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryAction("🎙", "Talk to Shraddha", Modifier.weight(1f)) { navController.navigate("voice") }
                    PrimaryAction("✉", "Ask Anything", Modifier.weight(1f)) { navController.navigate("chat") }
                }
            }
        }

        SectionTitle("QUICK ACCESS", "आपके रोज़मर्रा के AI टूल")
        FeatureGrid(
            Feature("📞", "Calls by Voice", "आवाज़ से कॉल", Mint) { navController.navigate("voice") },
            Feature("💬", "WhatsApp & SMS", "मैसेज बोलकर भेजें", Violet) { navController.navigate("quick_actions") },
            Feature("📷", "Visual Lens", "कैमरे से पूछें", Color(0xFFFFB85C)) { navController.navigate("visual_lens") },
            Feature("🖥", "Screen & PC", "स्क्रीन / PC कंट्रोल", Cyan) { navController.navigate("quick_actions") },
            Feature("📁", "File Manager", "फाइलें खोजें", Mint) { navController.navigate("quick_actions") },
            Feature("🌐", "Web Search", "नई जानकारी खोजें", Color(0xFFFFB85C)) { navController.navigate("chat") },
            Feature("⌨", "Code Assistant", "कोड और वेबसाइट", Violet) { navController.navigate("forge") },
            Feature("🧠", "Memory & Persona", "यादें और व्यक्तित्व", Mint) { navController.navigate("personality") }
        )

        SectionTitle("MAKE IT YOURS", "अपनी पसंद के अनुसार SHRADDHA")
        SettingLink("🎨", "Orb & Launcher Styles", "Classic  •  Energy  •  Neon  •  Aura") { navController.navigate("orb_style") }
        Spacer(Modifier.height(9.dp))
        SettingLink("✎", "Assistant Name", assistantName) { navController.navigate("profile") }
        Spacer(Modifier.height(9.dp))
        SettingLink("🤖", "AI Providers & Voice Settings", "अपना AI provider और voice configure करें") { navController.navigate("settings") }
        Spacer(Modifier.height(16.dp))
    }
}

private data class Feature(val icon: String, val title: String, val subtitle: String, val accent: Color, val action: () -> Unit)

@Composable
private fun FeatureGrid(vararg items: Feature) {
    items.toList().chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            pair.forEach { item -> FeatureTile(item, Modifier.weight(1f)) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(9.dp))
    }
}

@Composable
private fun FeatureTile(item: Feature, modifier: Modifier = Modifier) {
    Row(modifier.height(68.dp).clip(RoundedCornerShape(17.dp))
        .background(Brush.linearGradient(listOf(Color(0xE61A3150), Color(0xE6171A36))))
        .border(1.dp, item.accent.copy(alpha = 0.55f), RoundedCornerShape(17.dp))
        .clickable(onClick = item.action).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(item.icon, fontSize = 22.sp)
        Column(Modifier.padding(start = 8.dp)) {
            Text(item.title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(item.subtitle, color = Color(0xFFB7C8E0), fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp)) {
        Text(title, color = Cyan, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp)
        Text(subtitle, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xCC142944))
        .border(1.dp, Color(0x6676DFFF), CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun PrimaryAction(icon: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.height(48.dp).clip(RoundedCornerShape(16.dp))
        .background(Brush.horizontalGradient(listOf(Color(0xFF087CB8), Color(0xFF5533A9))))
        .border(1.dp, Color(0xAA7DEBFF), RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(icon, fontSize = 17.sp)
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp), textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun SettingLink(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Glass)
        .border(1.dp, Color(0x5576CFFF), RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 23.sp)
        Column(Modifier.weight(1f).padding(start = 11.dp)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFFB8C8E3), fontSize = 10.sp, maxLines = 1)
        }
        Text("›", color = Cyan, fontSize = 24.sp)
    }
}
