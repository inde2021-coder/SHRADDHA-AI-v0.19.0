package com.Lia.assistant.ui.screens.home

import android.content.Intent
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
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.fx.rememberDailyOrbTheme
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.VoiceSessionManager
import `in`.shraddha.ai.R
import `in`.shraddha.ai.operator.OperatorActivity

private val LiaPurple = Color(0xFF7C3AED)
private val LiaLavender = Color(0xFFF1EAFE)
private val LiaBorder = Color(0xFFE3D7FA)

private data class HomeAction(
    val title: String,
    val subtitle: String,
    val symbol: String,
    val onClick: () -> Unit
)

@Composable
fun HomeScreen(navController: NavController, appState: NovaAppState, modifier: Modifier = Modifier) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val orbState by VoiceSessionManager.state.collectAsState()
    val amplitude by VoiceSessionManager.amplitude.collectAsState()
    val assistantName = appState.assistantName.ifBlank { "Shraddha AI" }
    val dailyOrbTheme = rememberDailyOrbTheme()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val avatarHeight = (screenHeight * 0.39f).coerceIn(250.dp, 330.dp)

    val dailyActions = listOf(
        HomeAction("बात करें", "आवाज़ से निर्देश दें", "🎙") { navController.navigate("voice") },
        HomeAction("AI Chat", "लिखकर सवाल पूछें", "✦") { navController.navigate("chat") },
        HomeAction("Visual Lens", "कैमरे से समझें", "◉") { navController.navigate("visual_lens") },
        HomeAction("PC Control", "फोन से PC जोड़ें", "▣") { navController.navigate("device_center") }
    )
    val workActions = listOf(
        HomeAction("Live App Operator", "फोन ऐप पर काम करवाएँ", "⌘") {
            context.startActivity(Intent(context, OperatorActivity::class.java))
        },
        HomeAction("Multi-Agent Workspace", "बड़े काम को बाँटें", "◎") { navController.navigate("agent_council") },
        HomeAction("Expert Studio", "तकनीकी और विशेषज्ञ काम", "⚙") { navController.navigate("expert_studio") },
        HomeAction("Project Lab", "प्रोजेक्ट और प्रगति", "◇") { navController.navigate("project_lab") }
    )
    val systemActions = listOf(
        HomeAction("People Memory", "यादें और सुरक्षित बैकअप", "♥") { navController.navigate("data_vault") },
        HomeAction("Privacy & Security", "अनुमति और सुरक्षा", "⛨") { navController.navigate("privacy") },
        HomeAction("Plugins & Tools", "जुड़ी हुई सुविधाएँ", "⌘") { navController.navigate("plugins") },
        HomeAction("Smart Home", "स्मार्ट डिवाइस", "⌂") { navController.navigate("smart_home") }
    )

    Column(
        modifier = modifier.fillMaxSize().nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 104.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "Shraddha AI logo",
                modifier = Modifier.size(46.dp),
                contentScale = ContentScale.Fit
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(assistantName, color = colors.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF218B62)))
                    Text(
                        when (orbState.name) {
                            "LISTENING" -> " सुन रही हूँ"
                            "THINKING" -> " सोच रही हूँ"
                            "SPEAKING" -> " जवाब दे रही हूँ"
                            else -> " तैयार हूँ"
                        },
                        color = colors.success, fontSize = 11.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(Color.White)
                    .border(1.dp, LiaBorder, CircleShape)
                    .clickable { navController.navigate("settings") },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = LiaPurple)
            }
        }

        Box(
            Modifier.fillMaxWidth().height(avatarHeight)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFFEDE4FF), Color.White, Color(0xFFEAF4FF))))
                .border(1.dp, LiaBorder, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "YOUR PERSONAL AI",
                color = LiaPurple,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.7.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 16.dp)
            )
            Image(
                painter = painterResource(R.drawable.shraddha_ai_avatar),
                contentDescription = "$assistantName AI avatar",
                modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 48.dp),
                contentScale = ContentScale.Fit
            )
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp)
                    .size(70.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color.White, Color(0xFFE3D3FF), Color(0xFFC5A5FF))))
                    .border(1.dp, Color(0xFFD8C4FA), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                LiaOrb3D(
                    state = orbState,
                    size = 66.dp,
                    amplitude = amplitude,
                    style = dailyOrbTheme.style,
                    assistantName = assistantName
                )
            }
        }

        Text(
            dailyOrbTheme.style.displayName,
            color = LiaPurple,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HomePrimaryAction(
                text = "बात शुरू करें",
                modifier = Modifier.weight(1f),
                onClick = { navController.navigate("voice") }
            )
            HomeSecondaryAction(
                text = "Message",
                modifier = Modifier.weight(1f),
                onClick = { navController.navigate("chat") }
            )
        }

        HomeActionSection("DAILY ESSENTIALS", "रोज़ इस्तेमाल होने वाले मुख्य काम", dailyActions)
        HomeActionSection("COMMAND CENTER", "सभी काम के सिस्टम एक जगह", workActions)
        HomeActionSection("MEMORY & SYSTEM", "यादें, सुरक्षा और जुड़े हुए टूल", systemActions)
    }
}

@Composable
private fun HomeActionSection(
    title: String,
    subtitle: String,
    actions: List<HomeAction>
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(title, color = LiaPurple, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
        Text(subtitle, color = Color(0xFF705D89), fontSize = 10.sp)
        actions.chunked(2).forEach { rowActions ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowActions.forEach { action ->
                    HomeActionTile(action, Modifier.weight(1f))
                }
                if (rowActions.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HomeActionTile(action: HomeAction, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.height(94.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color.White, LiaLavender)))
            .border(1.dp, LiaBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = action.onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(31.dp).clip(CircleShape).background(Color(0xFFEDE4FF)),
                contentAlignment = Alignment.Center
            ) {
                Text(action.symbol, color = LiaPurple, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                action.title,
                color = Color(0xFF26133F),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Text(
            action.subtitle,
            color = Color(0xFF705D89),
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 7.dp)
        )
    }
}

@Composable
private fun HomePrimaryAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF7C3AED), Color(0xFFA855F7))))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomeSecondaryAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, LiaBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = LiaPurple, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
