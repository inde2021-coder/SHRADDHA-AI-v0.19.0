package com.Lia.assistant.ui.screens.vision

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.VoiceGatewaySettings
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import `in`.shraddha.ai.vision.LiveEyesCameraService
import kotlinx.coroutines.delay

@Composable
fun VisualLensScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val assistantName = NovaPreferences.getString(
        context, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.FULL_NAME
    ).trim().ifBlank { AssistantBrand.FULL_NAME }

    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var serviceState by remember { mutableStateOf(LiveEyesCameraService.getState(context)) }
    var liveObservation by remember { mutableStateOf(LiveEyesCameraService.getLatestObservation(context)) }
    var proactiveCompanion by remember {
        mutableStateOf(
            context.getSharedPreferences(LiveEyesCameraService.AMBIENT_PREFS, 0)
                .getBoolean(LiveEyesCameraService.PREF_PROACTIVE_COMPANION, true)
        )
    }
    var status by remember { mutableStateOf("कैमरा बंद है। Start दबाने पर Android permission और foreground notification दिखाई देंगे।") }

    fun startEyes() {
        val config = VoiceGatewaySettings.get(context)
        if (config.readError != null) {
            status = "Saved AI backend settings could not be read: " + config.readError
            return
        }
        if (!config.baseUrl.startsWith("https://") || config.gatewayToken.isBlank()) {
            status = "पहले Settings में सुरक्षित HTTPS AI backend URL और gateway token सेट करें।"
            return
        }
        try {
            startForegroundService(
                context,
                Intent(context, LiveEyesCameraService::class.java).setAction(LiveEyesCameraService.ACTION_START)
            )
            status = "Live Eyes शुरू हो रही हैं। कैमरा service की वास्तविक स्थिति की प्रतीक्षा करें।"
            serviceState = LiveEyesCameraService.getState(context)
        } catch (error: Exception) {
            status = "कैमरा शुरू नहीं हो पाया: " + (error.localizedMessage ?: "अनजान त्रुटि")
        }
    }

    fun stopEyes() {
        try {
            context.startService(Intent(context, LiveEyesCameraService::class.java).setAction(LiveEyesCameraService.ACTION_STOP))
            status = "Live Eyes बंद करने का अनुरोध भेजा गया।"
        } catch (error: Exception) {
            status = "कैमरा बंद नहीं हो पाया: " + (error.localizedMessage ?: "अनजान त्रुटि")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (granted) startEyes()
        else status = "कैमरा अनुमति नहीं मिली। बिना अनुमति कैमरा शुरू नहीं होगा।"
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                serviceState = LiveEyesCameraService.getState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(context) {
        while (true) {
            serviceState = LiveEyesCameraService.getState(context)
            liveObservation = LiveEyesCameraService.getLatestObservation(context)
            delay(500)
        }
    }

    val active = serviceState == LiveEyesCameraService.STATE_ACTIVE
    val starting = serviceState == LiveEyesCameraService.STATE_STARTING
    val stateText = when {
        active -> "दृश्य जागरूकता सक्रिय"
        starting -> "कैमरा service शुरू हो रही है"
        else -> "कैमरा बंद है"
    }

    Column(
        Modifier.fillMaxSize().nightSky().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        NovaTopBar(title = "VISUAL LENS", navigationIcon = {
            androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.textPrimary
                )
            }
        })

        NovaGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp, elevation = 7.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stateText, color = if (active) colors.success else if (starting) colors.accent else colors.textPrimary)
                Text(
                    "$assistantName की Live Eyes camera preview नहीं दिखातीं। यह camera frames लेकर आसपास की वस्तुएँ, जगह, लिखावट और स्पष्ट दृश्य बदलाव समझने के लिए configured HTTPS AI backend को भेजती हैं।",
                    color = colors.textPrimary
                )
                Text(
                    "Camera permission: " + if (cameraGranted) "granted" else "not granted",
                    color = colors.textSecondary
                )
                Text(
                    "Service status: " + serviceState,
                    color = colors.textSecondary
                )
                Text(status, color = colors.textSecondary)
                if (liveObservation.isNotBlank()) {
                    Text("LIVE SUBTITLES + OBJECT TRACKING", color = colors.accent)
                    Text(liveObservation, color = colors.textPrimary)
                }
            }
        }

        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Privacy & control", color = colors.textPrimary)
                Text(
                    "कैमरा चालू होने पर Android का privacy indicator और foreground notification दिखाई देगा। कोई camera preview इस स्क्रीन पर नहीं दिखेगा। Stop दबाने पर नए frames भेजना बंद होगा।",
                    color = colors.textSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Proactive Companion", color = colors.textPrimary)
                        Text(
                            "दृश्य में महत्वपूर्ण बदलाव होने पर Shraddha खुद छोटी बातचीत शुरू करेगी। बोलने के लिए पहले से चालू Voice Session जरूरी है; कैमरा अपने-आप माइक्रोफोन चालू नहीं करेगा।",
                            color = colors.textSecondary
                        )
                    }
                    Switch(
                        checked = proactiveCompanion,
                        onCheckedChange = { enabled ->
                            proactiveCompanion = enabled
                            context.getSharedPreferences(LiveEyesCameraService.AMBIENT_PREFS, 0)
                                .edit()
                                .putBoolean(LiveEyesCameraService.PREF_PROACTIVE_COMPANION, enabled)
                                .apply()
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NovaButton(
                        text = when {
                            active || starting -> "STOP EYES"
                            cameraGranted -> "START EYES"
                            else -> "ALLOW CAMERA"
                        },
                        onClick = {
                            if (active || starting) stopEyes()
                            else if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
                            else startEyes()
                        },
                        modifier = Modifier.weight(1f),
                        type = if (active || starting) NovaButtonType.SECONDARY else NovaButtonType.PRIMARY
                    )
                    NovaButton(
                        text = "Settings",
                        onClick = { navController.navigate("settings") },
                        modifier = Modifier.weight(1f),
                        type = NovaButtonType.SECONDARY
                    )
                }
                NovaButton(
                    text = "OPEN AR WORLD MAPPING",
                    onClick = { navController.navigate("ar_mapping") },
                    modifier = Modifier.fillMaxWidth(),
                    type = NovaButtonType.SECONDARY
                )
                NovaButton(
                    text = "Ask ${assistantName} about this view",
                    onClick = { navController.navigate("voice") },
                    modifier = Modifier.fillMaxWidth(),
                    type = NovaButtonType.SECONDARY,
                    enabled = active
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
