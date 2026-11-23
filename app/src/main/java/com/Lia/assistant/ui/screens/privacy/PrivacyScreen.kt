package com.Lia.assistant.ui.screens.privacy

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import com.Lia.assistant.voice.VoiceForegroundService
import com.Lia.assistant.voice.VoiceSessionManager
import `in`.shraddha.ai.future.LocalAuditTrail
import `in`.shraddha.ai.link.DeviceLinkManager
import `in`.shraddha.ai.screen.ScreenShareService
import `in`.shraddha.ai.vision.LiveEyesCameraService
import `in`.shraddha.ai.vision.RemoteCameraService

@Composable
fun PrivacyScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    var showStopConfirmation by remember { mutableStateOf(false) }
    var actionStatus by remember { mutableStateOf("") }

    fun stopAllLocalSessions() {
        VoiceSessionManager.stop()
        context.stopService(Intent(context, VoiceForegroundService::class.java))
        context.stopService(Intent(context, LiveEyesCameraService::class.java))
        context.stopService(Intent(context, RemoteCameraService::class.java))
        context.stopService(Intent(context, ScreenShareService::class.java))
        DeviceLinkManager.disconnect()
        LocalAuditTrail(context).append(
            "emergency_stop",
            "completed",
            "Stopped local voice, live camera, remote camera, screen-share services and disconnected the device relay."
        )
        actionStatus = "स्थानीय voice, camera, screen-share और device relay sessions रोकने का अनुरोध पूरा हुआ। Paired device records revoke नहीं हुए; उन्हें Device Center में अलग से revoke करें।"
    }

    Column(
        modifier = modifier.fillMaxSize().nightSky().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        NovaTopBar(title = "PRIVACY & SECURITY", navigationIcon = {
            androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }
        })

        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Local-first memory & API keys", color = colors.accent, fontSize = 16.sp)
                Text(
                    "Conversation memory can be stored locally in encrypted app storage. Optional cloud memory sync uses the configured Firebase project and requires its account/rules setup and user consent. The Gemini key is encrypted with Android Keystore AES-GCM; provider-specific key storage must be reviewed separately. API keys, biometric templates and pairing secrets are intentionally excluded from normal backups.",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }

        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Voice, camera & screen data", color = colors.accent, fontSize = 16.sp)
                Text(
                    "Voice audio is sent to the provider/backend configured for the active voice mode over its supported secure connection. Visual Lens sends selected camera frames to the configured HTTPS AI backend only while its foreground service is running. Accessibility actions run on-device, but user-requested screen/visual sharing may send encrypted frames to a verified paired device. Android permission indicators and foreground notifications remain visible while capture services run.",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }

        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Device pairing & file transfer", color = colors.accent, fontSize = 16.sp)
                Text(
                    "Paired-device visual frames, clipboard text, quick-share attachments and large-file chunks use ECDH-derived AES-GCM encryption after fingerprint verification. The relay enforces category permissions and one-time grants but cannot inspect encrypted attachment contents. Remote actions require target-side approval. File transfers verify chunk indexes, byte lengths and SHA-256 before saving; the current Android transfer limit is 100 MiB. Malware scanning and physical multi-device interruption testing are not yet verified. Disconnecting this phone does not erase the other device's stored files.",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }

        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Audit & user control", color = colors.accent, fontSize = 16.sp)
                Text(
                    "The local audit trail stores encrypted event metadata and is designed not to store passwords, API tokens, raw biometrics, screen frames or message bodies. You can stop capture and disconnect the current device's relay session below. To revoke a paired device permanently, use the Device Center's owner-authorized revoke control.",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                NovaButton(
                    text = "STOP ALL LOCAL LIVE SESSIONS",
                    onClick = { showStopConfirmation = true },
                    modifier = Modifier.fillMaxWidth(),
                    type = NovaButtonType.SECONDARY
                )
                if (actionStatus.isNotBlank()) Text(actionStatus, color = colors.textSecondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    if (showStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showStopConfirmation = false },
            title = { Text("Stop all live sessions?") },
            text = { Text("Voice, live camera, remote camera, screen sharing and this phone's device relay will stop. This does not delete paired-device records or revoke the other device.") },
            confirmButton = {
                TextButton(onClick = {
                    stopAllLocalSessions()
                    showStopConfirmation = false
                }) { Text("Stop all") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}
