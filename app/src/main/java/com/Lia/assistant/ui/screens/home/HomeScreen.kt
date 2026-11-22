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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import `in`.shraddha.ai.R
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.NovaOrbState
import com.Lia.assistant.voice.VoiceSessionManager

private val NeonPurple = Color(0xFF9D4DFF)
private val NeonPink = Color(0xFFE45DFF)
private val NeonBlue = Color(0xFF53CFFF)
private val MutedText = Color(0xFFB9B5CC)

@Composable
fun HomeScreen(navController: NavController, appState: NovaAppState, modifier: Modifier = Modifier) {
    val colors = LocalNovaColors.current
    val voiceState by VoiceSessionManager.state.collectAsState()
    val assistantName = appState.assistantName.ifBlank { AssistantBrand.FULL_NAME }
    var askText by remember { mutableStateOf("") }
    val voiceLabel = when (voiceState) {
        NovaOrbState.LISTENING -> "Listening…"
        NovaOrbState.THINKING -> "Thinking…"
        NovaOrbState.SPEAKING -> "Speaking…"
        NovaOrbState.CONNECTING -> "Connecting…"
        NovaOrbState.ERROR -> "Voice connection needs attention"
        else -> "Always here when you need me"
    }

    fun openChat() {
        val prompt = askText.trim()
        if (prompt.isNotEmpty()) {
            navController.currentBackStackEntry?.savedStateHandle?.set("home_prompt", prompt)
        }
        askText = ""
        navController.navigate("chat") { launchSingleTop = true }
    }

    Column(
        modifier = modifier.fillMaxSize().nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 17.dp)
            .padding(bottom = 104.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderButton(Icons.Default.Menu, "Quick actions") { navController.navigate("quick_actions") }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(assistantName.uppercase(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.1.sp)
                Text("YOUR AI ASSISTANT", color = Color(0xFFBBA4DF), fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.3.sp)
            }
            Spacer(Modifier.weight(1f))
            HeaderButton(Icons.Default.Settings, "Settings") { navController.navigate("settings") }
        }

        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(326.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = appState.assistantAvatarUri ?: R.drawable.shraddha_ai_hero,
                contentDescription = "$assistantName portrait",
                modifier = Modifier.size(width = 310.dp, height = 310.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-2).dp)
                    .size(82.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF35105D), Color(0xFF16062D), Color(0xFF090817))))
                    .border(2.dp, NeonPurple, CircleShape)
                    .clickable { navController.navigate("voice") },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(67.dp).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color(0xFF5E1B9D), Color(0xFF251047))))
                        .border(1.dp, Color(0xFFDC8BFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Talk to $assistantName", tint = Color.White, modifier = Modifier.size(31.dp))
                }
            }
        }

        Text("Tap to Talk", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(14.dp))
            Text(voiceLabel, color = MutedText, fontSize = 10.sp)
        }

        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                .background(Color(0xFF111020))
                .border(1.dp, Color(0xFF44305D), RoundedCornerShape(17.dp))
                .padding(start = 12.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(30.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(NeonPurple, Color(0xFF5D3CB3)))),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.SmartToy, null, tint = Color.White, modifier = Modifier.size(17.dp)) }
            Spacer(Modifier.width(9.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (askText.isBlank()) Text("Ask me anything…", color = Color(0xFF8C879C), fontSize = 12.sp)
                BasicTextField(
                    value = askText,
                    onValueChange = { askText = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(NeonPurple, Color(0xFF7239D2))))
                    .clickable { openChat() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to chat", tint = Color.White, modifier = Modifier.size(19.dp)) }
        }

        Spacer(Modifier.height(13.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            HomeShortcut("Chat", "Ask with text", "CHAT", Modifier.weight(1f)) { navController.navigate("chat") }
            HomeShortcut("Visual Lens", "Ask with camera", "LENS", Modifier.weight(1f)) { navController.navigate("visual_lens") }
            HomeShortcut("Create", "Build a website", "FORGE", Modifier.weight(1f)) {
                navController.navigate("quick_actions")
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("QUICK ACCESS", color = Color(0xFF8C6EC6), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(5.dp))
        Text("Your assistant. Your tools. One place.", color = MutedText, fontSize = 10.sp)
    }
}

@Composable
private fun HeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF171527))
            .border(1.dp, Color(0x553A3156), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color(0xFFE8DDFB), modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun HomeShortcut(title: String, subtitle: String, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF121126))
            .border(1.dp, Color(0x443A2A58), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(tag, color = NeonBlue, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(5.dp))
        Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MutedText, fontSize = 8.sp, maxLines = 1)
    }
}
