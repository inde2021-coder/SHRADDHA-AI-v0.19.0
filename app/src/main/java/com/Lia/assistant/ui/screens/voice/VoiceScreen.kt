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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.Lia.assistant.data.VoiceGatewaySettings
import com.Lia.assistant.data.NovaAppState
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaVoiceVisualizer
import com.Lia.assistant.ui.fx.LiaOrb3D
import com.Lia.assistant.ui.fx.ListeningEdgeGlow
import com.Lia.assistant.ui.fx.NovaOrbStyle
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.SerifFontFamily
import com.Lia.assistant.ui.theme.depthSurface
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
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasKey by remember { mutableStateOf(VoiceGatewaySettings.isConfigured(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasKey = VoiceGatewaySettings.isConfigured(context)
                hasRecordAudioPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val state by VoiceSessionManager.state.collectAsState()
    val amplitude by VoiceSessionManager.amplitude.collectAsState()
    val isActive by VoiceSessionManager.isActive.collectAsState()
    val statusText by VoiceSessionManager.statusText.collectAsState()

    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val orbSize = screenWidth * 0.70f

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .nightSky()
    ) {
        ListeningEdgeGlow()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: Dismiss/Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
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

            // Center: 70% width 3D Orb & Status
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
                        text = "Secure Voice Backend Required",
                        fontFamily = SerifFontFamily,
                        fontStyle = FontStyle.Italic,
                        fontSize = 20.sp,
                        color = colors.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NovaButton(
                        text = "Configure Secure Backend",
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
                        fontSize = 20.sp,
                        color = colors.textPrimary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (state == NovaOrbState.SPEAKING) {
                        NovaVoiceVisualizer(
                            amplitude = amplitude,
                            barCount = 48,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            }

            if (hasKey && hasRecordAudioPermission) {
                // One microphone control: tap once to end the active session,
                // tap again to start it. The close control above only dismisses this screen.
                val micShape = RoundedCornerShape(50)
                Box(
                    modifier = Modifier
                        .depthSurface(shape = micShape, elevation = 6.dp)
                        .clip(micShape)
                        .background(colors.surfaceGlass)
                        .clickable {
                            if (isActive) VoiceForegroundService.stop(context)
                            else if (hasKey && hasRecordAudioPermission) VoiceForegroundService.start(context)
                        }
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = if (isActive) "Turn microphone off" else "Turn microphone on",
                            tint = if (isActive) colors.accent else colors.textPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = if (isActive) "MICROPHONE ON · TAP TO TURN OFF" else "MICROPHONE OFF · TAP TO TURN ON",
                            color = colors.textPrimary,
                            fontSize = 12.sp
                        )
                    }
                }

            }
        }
    }
}
