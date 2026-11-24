package com.Lia.assistant.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import android.content.Intent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import `in`.shraddha.ai.mobile.MobileCommandCenterActivity

private val ShraddhaViolet = Color(0xFF8B5CF6)
private val ShraddhaBorder = Color(0xFF4C3D70)

@Composable
fun HomeScreen(navController: NavController, appState: NovaAppState, modifier: Modifier = Modifier) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current
    val orbState by VoiceSessionManager.state.collectAsState()
    val amplitude by VoiceSessionManager.amplitude.collectAsState()
    var commandText by remember { mutableStateOf("") }
    val assistantName = appState.assistantName.ifBlank { "Shraddha AI" }
    val design = appState.homeDesign
    val dailyOrbTheme = rememberDailyOrbTheme()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val avatarHeight = when (design) {
        "ROYAL_3D" -> (screenHeight * 0.44f).coerceIn(280.dp, 360.dp)
        "COSMIC_ORB" -> (screenHeight * 0.32f).coerceIn(220.dp, 290.dp)
        "AI_ROBOT" -> (screenHeight * 0.48f).coerceIn(300.dp, 390.dp)
        "EXECUTIVE" -> (screenHeight * 0.25f).coerceIn(180.dp, 230.dp)
        else -> (screenHeight * 0.39f).coerceIn(250.dp, 330.dp)
    }

    fun sendCommand() {
        val prompt = commandText.trim()
        if (prompt.isNotEmpty()) {
            commandText = ""
            context.startActivity(
                Intent(context, MobileCommandCenterActivity::class.java)
                    .putExtra("mobile_control_command", prompt)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    Column(
        modifier = modifier.fillMaxSize().nightSky()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 100.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "Shraddha AI logo",
                modifier = Modifier.size(44.dp),
                contentScale = ContentScale.Fit
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(assistantName, color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(colors.success))
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
        }

        Box(
            Modifier.fillMaxWidth().height(avatarHeight)
                .clip(RoundedCornerShape(30.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF17142B), Color(0xFF222044), Color(0xFF111B32))))
                .border(1.dp, ShraddhaBorder, RoundedCornerShape(30.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "YOUR PERSONAL AI",
                color = Color(0xFFC4B5FD),
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
            )
            Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.shraddha_ai_avatar),
                contentDescription = "$assistantName AI avatar",
                modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 30.dp, bottom = 46.dp),
                contentScale = ContentScale.Fit
            )
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp)
                    .size(72.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFFF5F0FF), Color(0xFFBDA4FF), Color(0xFF6044B8))))
                    .border(1.dp, Color(0xFFD8C4FA), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                LiaOrb3D(
                    state = orbState,
                    size = 68.dp,
                    amplitude = amplitude,
                    style = dailyOrbTheme.style,
                    assistantName = assistantName
                )
            }
        }

        Text(
            when (orbState.name) {
                "LISTENING" -> "मैं सुन रही हूँ…"
                "THINKING" -> "आपकी बात समझ रही हूँ…"
                "SPEAKING" -> "आपको जवाब दे रही हूँ…"
                else -> "बताइए, आज मैं क्या करूँ?"
            },
            color = colors.textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)
        )

        OutlinedTextField(
            value = commandText,
            onValueChange = { commandText = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Shraddha को लिखकर कमांड दें…") },
            trailingIcon = {
                IconButton(onClick = { sendCommand() }, enabled = commandText.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send command", tint = ShraddhaViolet)
                }
            },
            shape = RoundedCornerShape(20.dp),
            maxLines = 3,
            singleLine = false
        )

        Text(
            "वॉइस कमांड के लिए बीच वाले Orb को छुएँ • बाकी काम कमांड से",
            color = colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 10.dp)
        )
    }
}
