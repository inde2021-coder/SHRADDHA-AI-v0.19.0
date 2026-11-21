package com.Lia.assistant.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.ui.components.AccessKeyBanner
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.fx.NovaOrbStyle
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.SerifFontFamily
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.VoiceSessionManager
import java.util.Calendar

@Composable
fun HomeScreen(
    navController: NavController,
    appState: NovaAppState,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val orbState by VoiceSessionManager.state.collectAsState()
    val amplitude by VoiceSessionManager.amplitude.collectAsState()
    val voiceStatus by VoiceSessionManager.statusText.collectAsState()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val orbSize = (screenWidth * 0.55f).coerceAtMost(260.dp)

    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Welcome back"
    }

    val assistantName = appState.assistantName.ifBlank { "Shraddha AI" }
    val trySayingQueries = listOf(
        "\"$assistantName, ek cafe ki website banao\"",
        "\"Call Mummy\"",
        "\"Open YouTube and search music\"",
        "\"What's the weather like today?\""
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "PERSONAL AI SYSTEM",
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent
                )
                Text(
                    text = assistantName,
                    fontFamily = SerifFontFamily,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceGlass)
                    .clickable { navController.navigate("profile") }
                    .semantics { contentDescription = "Open profile settings" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Profile",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        AccessKeyBanner(
            onActivateClick = { navController.navigate("profile") },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "$greetingPrefix, ${appState.userName}",
            fontFamily = SerifFontFamily,
            fontSize = 28.sp,
            color = colors.textPrimary
        )

        Spacer(modifier = Modifier.height(28.dp))

        Box(
            modifier = Modifier
                .size(orbSize)
                .clickable { navController.navigate("voice") }
                .semantics { contentDescription = "Open $assistantName voice assistant" },
            contentAlignment = Alignment.Center
        ) {
            LiaOrb3D(
                state = orbState,
                size = orbSize,
                amplitude = amplitude,
                style = NovaOrbStyle.fromString(appState.orbStyle),
                assistantName = assistantName
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Tap me to talk",
            fontFamily = SerifFontFamily,
            fontSize = 15.sp,
            color = colors.textSecondary
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NovaButton(
                text = "Talk",
                onClick = { navController.navigate("voice") },
                type = NovaButtonType.PRIMARY,
                icon = Icons.Default.Mic
            )
            NovaButton(
                text = "Type",
                onClick = { navController.navigate("chat") },
                type = NovaButtonType.SECONDARY
            )
        }

        Spacer(modifier = Modifier.height(36.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "TRY SAYING",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = colors.accent,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                colors.surfaceRaised,
                                colors.surface,
                                colors.background
                            )
                        ),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = greeting,
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )
                        Text(
                            text = appState.userName,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(220.dp)
                            .graphicsLayer {
                                rotationY = if (idx % 2 == 0) -4f else 4f
                                cameraDistance = 12f * density
                            }
                            .clickable { navController.navigate("chat") }
                    ) {
                        Text(
                            text = when (orbState.name) {
                                "LISTENING" -> "● LISTENING"
                                "THINKING" -> "● THINKING"
                                "SPEAKING" -> "● SPEAKING"
                                "ERROR" -> "● CHECK AUDIO"
                                else -> "● STANDBY"
                            },
                            fontSize = 9.sp,
                            letterSpacing = 0.7.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (orbState.name == "ERROR") colors.error else colors.success
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .size(orbSize)
                        .clickable { navController.navigate("voice") },
                    contentAlignment = Alignment.Center
                ) {
                    LiaOrb3D(
                        state = orbState,
                        size = orbSize,
                        amplitude = amplitude,
                        style = NovaOrbStyle.fromString(appState.orbStyle),
                        assistantName = appState.assistantName
                    )
                }
                Text(
                    text = voiceStatus.takeIf { it.isNotBlank() } ?: "Tap the orb to start a voice session",
                    maxLines = 2,
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    NovaButton(
                        text = "Talk",
                        onClick = { navController.navigate("voice") },
                        modifier = Modifier.weight(1f),
                        type = NovaButtonType.PRIMARY,
                        icon = Icons.Default.Mic
                    )
                    NovaButton(
                        text = "Type",
                        onClick = { navController.navigate("chat") },
                        modifier = Modifier.weight(1f),
                        type = NovaButtonType.SECONDARY
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "YOUR WORKSPACE",
                    fontSize = 11.sp,
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent
                )
                Text(
                    text = "Everything in one place",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        workspaces.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { item ->
                    NovaGlassCard(
                        modifier = Modifier
                            .weight(1f)
                            .height(116.dp)
                            .clickable { navController.navigate(item.third) },
                        cornerRadius = 20.dp,
                        elevation = 3.dp
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = item.first,
                                fontSize = 11.sp,
                                letterSpacing = 0.2.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(
                                text = item.second,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }

    }
}
