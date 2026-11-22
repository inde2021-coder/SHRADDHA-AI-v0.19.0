package com.Lia.assistant.ui.screens.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.navigation.NavController
import com.Lia.assistant.data.VoiceGatewaySettings
import `in`.shraddha.ai.vision.LiveEyesCameraService

private val EyesBackground = Color(0xFF07111F)
private val EyesPanel = Color(0xFF10243B)
private val EyesAccent = Color(0xFF38DFFF)

@Composable
fun VisualLensScreen(navController: NavController) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Shraddha की आँखें अभी बंद हैं।") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (granted) {
            val config = VoiceGatewaySettings.get(context)
            if (!config.baseUrl.startsWith("https://") || config.gatewayToken.isBlank()) {
                status = "पहले Settings में सुरक्षित HTTPS AI backend URL और gateway token सेट करें।"
            } else {
                runCatching {
                    startForegroundService(context, Intent(context, LiveEyesCameraService::class.java).setAction(LiveEyesCameraService.ACTION_START))
                    running = true
                    status = "Live Eyes चालू हैं। प्रीव्यू नहीं दिखेगा; AI समय-समय पर दृश्य समझेगा।"
                }.onFailure { status = "कैमरा शुरू नहीं हो पाया: ${it.localizedMessage ?: "अनजान त्रुटि"}" }
            }
        } else status = "कैमरा अनुमति नहीं मिली। बिना अनुमति कैमरा शुरू नहीं होगा।"
    }

    Column(
        Modifier.fillMaxSize().background(EyesBackground).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("SHRADDHA AI EYES", color = EyesAccent)
        Column(
            Modifier.fillMaxWidth().weight(1f).background(EyesPanel, RoundedCornerShape(22.dp)).padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(if (running) "दृश्य जागरूकता सक्रिय" else "कैमरा = Shraddha की आँखें", color = Color.White)
            Spacer(Modifier.height(14.dp))
            Text(
                "अब कैमरा प्रीव्यू स्क्रीन पर नहीं खुलेगा। Shraddha पीछे से कैमरा फ्रेम लेकर आसपास की वस्तुएँ, जगह, लिखावट और दृश्य में बदलाव समझेगी। हर कुछ सेकंड में नया दृश्य जाँचा जाएगा; पुराने फ्रेम कतार में जमा नहीं होंगे।",
                color = Color(0xFFD0DCEB)
            )
            Spacer(Modifier.height(18.dp))
            Text(status, color = if (running) EyesAccent else Color.LightGray)
        }
        Text(
            "गोपनीयता: Android का कैमरा संकेत और स्थायी सूचना दिखाई देगी। आप कभी भी बंद कर सकते हैं। चालू सत्र में चुने हुए दृश्य सुरक्षित HTTPS AI सर्वर पर विश्लेषण के लिए भेजे जाते हैं।",
            color = Color.LightGray
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (running) {
                        context.startService(Intent(context, LiveEyesCameraService::class.java).setAction(LiveEyesCameraService.ACTION_STOP))
                        running = false
                        status = "Live Eyes बंद कर दी गईं। अब कोई नया कैमरा फ्रेम नहीं भेजा जाएगा।"
                    } else if (!cameraGranted) permission.launch(Manifest.permission.CAMERA)
                    else {
                        val config = VoiceGatewaySettings.get(context)
                        if (!config.baseUrl.startsWith("https://") || config.gatewayToken.isBlank()) {
                            status = "पहले Settings में AI backend URL और gateway token सेट करें।"
                        } else runCatching {
                            startForegroundService(context, Intent(context, LiveEyesCameraService::class.java).setAction(LiveEyesCameraService.ACTION_START))
                            running = true
                            status = "Live Eyes चालू हैं।"
                        }.onFailure { status = "शुरू नहीं हो पाया: ${it.localizedMessage ?: "अनजान त्रुटि"}" }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (running) Color(0xFF9B3449) else Color(0xFF126A91))
            ) { Text(if (running) "STOP EYES" else if (cameraGranted) "START EYES" else "ALLOW CAMERA") }
            Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = EyesPanel)) {
                Text("वापस")
            }
        }
    }
}
