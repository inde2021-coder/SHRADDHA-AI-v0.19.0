package com.Lia.assistant.ui.screens.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.Lia.assistant.data.ApiKeyStore
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaVoiceVisualizer
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.fx.ListeningEdgeGlow
import com.Lia.assistant.ui.fx.NovaOrbStyle
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.SerifFontFamily
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.NovaOrbState
import com.Lia.assistant.voice.VoiceForegroundService
import com.Lia.assistant.voice.VoiceSessionManager

@Composable
fun VoiceScreen(
    navController: NavController,
    appState: NovaAppState,
    modifier: Modifier = Modifier
) {
    val colors = LocalNovaColors.current
    val context = LocalContext.current

    val state by VoiceSessionManager.state.collectAsState()
    val amplitude by VoiceSessionManager.amplitude.collectAsState()
    val isActive by VoiceSessionManager.isActive.collectAsState()
    val statusText by VoiceSessionManager.statusText.collectAsState()
    val lastError by VoiceSessionManager.lastError.collectAsState()

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val orbSize = (screenWidth * 0.62f).coerceIn(160.dp, 260.dp)

    var hasRecordAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasRecordAudioPermission = isGranted
        if (isGranted) {
            VoiceForegroundService.start(context)
        }
    }

    val hasKey = ApiKeyStore.hasKey(context)

    LaunchedEffect(hasRecordAudioPermission, hasKey) {
        if (!hasRecordAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (hasKey && !VoiceSessionManager.isActive.value) {
            VoiceForegroundService.start(context)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
    ) {
        ListeningEdgeGlow()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: identity and dismiss control
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = appState.assistantName, fontSize = 18.sp, color = colors.textPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(text = "VOICE INTERFACE  •  PRIVATE SESSION", fontSize = 9.sp, letterSpacing = 1.1.sp, color = colors.textTertiary)
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceGlass)
                        .clickable { navController.popBackStack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = colors.textPrimary
                    )
                }
            }

            // Center: adaptive 3D Orb and live session status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (!hasKey) {
                    LiaOrb3D(
                        state = NovaOrbState.ERROR,
                        size = orbSize,
                        amplitude = 0f,
                        assistantName = appState.assistantName
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "API Key Required",
                        fontFamily = SerifFontFamily,
                        fontStyle = FontStyle.Italic,
                        fontSize = 20.sp,
                        color = colors.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NovaButton(
                        text = "Open Settings",
                        onClick = { navController.navigate("settings") },
                        type = NovaButtonType.PRIMARY
                    )
                } else if (!hasRecordAudioPermission) {
                    LiaOrb3D(
                        state = NovaOrbState.ERROR,
                        size = orbSize,
                        amplitude = 0f,
                        assistantName = appState.assistantName
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Microphone Permission Required",
                        fontFamily = SerifFontFamily,
                        fontStyle = FontStyle.Italic,
                        fontSize = 18.sp,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NovaButton(
                        text = "Grant Permission",
                        onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    )
                } else {
                    Box(
                        modifier = Modifier.size(orbSize),
                        contentAlignment = Alignment.Center
                    ) {
                        LiaOrb3D(
                            state = state,
                            size = orbSize,
                            amplitude = amplitude,
                            style = NovaOrbStyle.fromString(appState.orbStyle),
                            assistantName = appState.assistantName
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Text(
                        text = statusText,
                        fontFamily = SerifFontFamily,
                        fontStyle = FontStyle.Italic,
                        fontSize = 19.sp,
                        color = colors.textPrimary
                    )

                    if (state == NovaOrbState.ERROR) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = lastError.orEmpty(),
                            fontSize = 12.sp,
                            color = colors.error
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        NovaButton(
                            text = "Retry connection",
                            onClick = { VoiceSessionManager.retry(context) },
                            type = NovaButtonType.PRIMARY
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (state == NovaOrbState.SPEAKING) {
                        NovaVoiceVisualizer(
                            amplitude = amplitude,
                            barCount = 36,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
            }

            // One persistent microphone control: the foreground session keeps running
            // after this screen is closed and stops only when the user deactivates it.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(82.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Color(0xFF8B2535) else colors.accent)
                        .clickable {
                            if (isActive) {
                                VoiceForegroundService.stop(context)
                            } else if (hasRecordAudioPermission) {
                                VoiceForegroundService.start(context)
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isActive) "Deactivate microphone" else "Activate microphone",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isActive) "MICROPHONE ACTIVE • TAP TO DEACTIVATE" else "MICROPHONE OFF • TAP TO ACTIVATE",
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp,
                    color = colors.textSecondary
                )
            }
        }
    }
}
