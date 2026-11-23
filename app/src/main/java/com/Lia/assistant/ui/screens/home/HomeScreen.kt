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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import android.content.Intent
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.fx.rememberDailyOrbTheme
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.VoiceSessionManager
import `in`.shraddha.ai.R
import `in`.shraddha.ai.operator.OperatorActivity

private val Accent = Color(0xFFB58BFF)
private val Mint = Color(0xFF78E4D6)

@Composable
fun HomeScreen(navController: NavController, appState: NovaAppState, modifier: Modifier = Modifier) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val orbState by VoiceSessionManager.state.collectAsState()
    val amplitude by VoiceSessionManager.amplitude.collectAsState()
    val assistantName = appState.assistantName.ifBlank { "Shraddha AI" }
    val dailyOrbTheme = rememberDailyOrbTheme()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val avatarHeight = (screenHeight * 0.44f).coerceIn(250.dp, 390.dp)

    Column(
        modifier = modifier.fillMaxSize().nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 100.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "Shraddha AI logo",
                modifier = Modifier.size(44.dp),
                contentScale = ContentScale.Fit
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(assistantName, color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (orbState.name) {
                        "LISTENING" -> "Listening"
                        "THINKING" -> "Thinking"
                        "SPEAKING" -> "Speaking"
                        else -> "Ready"
                    },
                    color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(Color(0xCC19243B))
                    .border(1.dp, Color(0x667D52D8), CircleShape)
                    .clickable { navController.navigate("settings") },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }

        Box(
            Modifier.fillMaxWidth().height(avatarHeight)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF21143D), Color(0xFF111426), Color(0xFF080A14))))
                .border(1.dp, Color(0x557D52D8), RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.shraddha_ai_avatar),
                contentDescription = "$assistantName AI avatar",
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp),
                contentScale = ContentScale.Fit
            )
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    .size(76.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFFE2C9FF), Color(0xFF8147D6), Color(0xCC210F46))))
                    .border(1.5.dp, Color(0xCCDCBCFF), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                LiaOrb3D(
                    state = orbState,
                    size = 72.dp,
                    amplitude = amplitude,
                    style = dailyOrbTheme.style,
                    assistantName = assistantName
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(dailyOrbTheme.style.displayName, color = Accent, fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(16.dp))

        // Integrated controls live inside the main Shraddha AI home design.
        ControlCenter(
            onLiveOperator = { context.startActivity(Intent(context, OperatorActivity::class.java)) },
            onVisualLens = { navController.navigate("visual_lens") },
            onConnectPc = { navController.navigate("device_center") },
            onTalk = { navController.navigate("voice") }
        )

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xCC17152B))
                .border(1.dp, Color(0x447D52D8), RoundedCornerShape(18.dp))
                .clickable { navController.navigate("chat") }
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Message $assistantName…", color = Color(0xFFB8A9D4), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.GraphicEq, contentDescription = "Open chat", tint = Mint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ControlCenter(
    onLiveOperator: () -> Unit,
    onVisualLens: () -> Unit,
    onConnectPc: () -> Unit,
    onTalk: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(25.dp))
            .background(Brush.verticalGradient(listOf(Color(0xDD1B2340), Color(0xE6091425))))
            .border(1.dp, Color(0x667D72C8), RoundedCornerShape(25.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SHRADDHA CONTROL CENTER", color = Color.White, fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 1.05.sp)
                Text("सभी मुख्य सिस्टम एक ही स्क्रीन से", color = Color(0xFFB7C6E8), fontSize = 10.sp)
            }
            Text("● LIVE", color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            ControlTile("Talk to Shraddha", "बोलकर काम करें", "🎙", Modifier.weight(1f), onTalk)
            ControlTile("Live App Operator", "फोन ऐप चलाएँ", "⌘", Modifier.weight(1f), onLiveOperator)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            ControlTile("Visual Lens", "कैमरे से पूछें", "◉", Modifier.weight(1f), onVisualLens)
            ControlTile("PC Control", "कंप्यूटर जोड़ें", "▣", Modifier.weight(1f), onConnectPc)
        }
    }
}

@Composable
private fun ControlTile(
    title: String,
    subtitle: String,
    symbol: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier.height(82.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF28385B), Color(0xFF17263E))))
            .border(1.dp, Color(0x665F8CB2), RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, color = Mint, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 7.dp))
        }
        Text(subtitle, color = Color(0xFFB5C6DC), fontSize = 9.sp,
            modifier = Modifier.padding(top = 5.dp))
    }
}
