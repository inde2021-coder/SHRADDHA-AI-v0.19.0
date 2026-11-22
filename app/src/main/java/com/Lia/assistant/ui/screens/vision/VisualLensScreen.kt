package com.Lia.assistant.ui.screens.vision

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.Lia.assistant.data.VoiceGatewaySettings
import `in`.shraddha.ai.link.DeviceLinkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

private val LensBackground = Color(0xFF07111F)
private val LensPanel = Color(0xFF10243B)
private val LensAccent = Color(0xFF38DFFF)

@Composable
fun VisualLensScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var running by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("आपको मेरे सामने क्या दिखाई दे रहा है?") }
    var answer by remember { mutableStateOf("कैमरा शुरू करने के लिए अनुमति दें।") }
    val controller = remember(context) { LifecycleCameraController(context).apply {
        cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
        imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (!granted) answer = "कैमरा अनुमति नहीं मिली। Visual Lens चलाने के लिए Settings में अनुमति दें।"
    }

    DisposableEffect(controller, lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { running = false; controller.unbind() }
    }

    LaunchedEffect(running, cameraGranted) {
        if (!running || !cameraGranted) return@LaunchedEffect
        val config = VoiceGatewaySettings.get(context)
        if (config.baseUrl.isBlank() || config.gatewayToken.isBlank()) {
            answer = "पहले Settings में सुरक्षित HTTPS AI backend URL और gateway token सेट करें।"
            running = false
            return@LaunchedEffect
        }
        while (isActive && running) {
            if (!busy) {
                busy = true
                answer = captureAndAnalyze(controller, context, config.baseUrl, config.gatewayToken, question)
                busy = false
            }
            delay(2_800)
        }
    }

    Column(
        Modifier.fillMaxSize().background(LensBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LIVE VISUAL LENS", color = LensAccent)
            Text("कैमरा नियंत्रण आपके हाथ में", color = Color.LightGray)
        }
        AndroidView(
            factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; this.controller = controller } },
            modifier = Modifier.fillMaxWidth().weight(0.44f).background(Color.Black, RoundedCornerShape(20.dp))
        )
        Text("कैमरा दृश्य का विश्लेषण केवल Live Lens चालू रहने पर होगा। बंद करते ही नए फ्रेम भेजना रुक जाएगा।", color = Color.LightGray)
        OutlinedTextField(
            value = question, onValueChange = { question = it },
            label = { Text("Shraddha से क्या पूछना है?") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1, maxLines = 3
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                if (!cameraGranted) permission.launch(Manifest.permission.CAMERA)
                else { running = !running; answer = if (running) "Live Lens चालू हो रहा है…" else "Live Lens बंद है।" }
            }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (running) Color(0xFF9B3449) else Color(0xFF126A91))) {
                Text(if (running) "STOP LIVE" else if (cameraGranted) "START LIVE" else "ALLOW CAMERA")
            }
            Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = LensPanel)) { Text("बंद करें") }
        }
        Column(Modifier.fillMaxWidth().weight(0.56f).background(LensPanel, RoundedCornerShape(18.dp)).padding(14.dp)) {
            Text(if (busy) "Shraddha दृश्य समझ रही है…" else "SHRADDHA AI", color = LensAccent)
            Spacer(Modifier.height(8.dp))
            Text(answer, color = Color.White)
        }
    }
}

private suspend fun captureAndAnalyze(
    controller: LifecycleCameraController,
    context: android.content.Context,
    baseUrl: String,
    token: String,
    question: String
): String {
    val file = File(context.cacheDir, "visual-lens-${System.nanoTime()}.jpg")
    val saved = suspendCancellableCoroutine<Boolean> { continuation ->
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        controller.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                if (continuation.isActive) continuation.resume(true)
            }
            override fun onError(exception: ImageCaptureException) {
                if (continuation.isActive) continuation.resume(false)
            }
        })
    }
    if (!saved || !file.isFile) { file.delete(); return "कैमरा फ्रेम नहीं मिल पाया। कैमरा की स्थिति जाँचें।" }
    return try {
        val jpeg = file.readBytes()
        if (jpeg.isEmpty() || jpeg.size > 2_500_000) return "फ्रेम का आकार सुरक्षित सीमा से बाहर है।"
        suspendCancellableCoroutine { continuation ->
            DeviceLinkManager.analyzeScreen(baseUrl, token, "gemini", question, jpeg, "general") { result ->
                jpeg.fill(0)
                if (continuation.isActive) continuation.resume(result.getOrElse { "Vision सेवा उपलब्ध नहीं: ${it.message ?: "अनजान त्रुटि"}" })
            }
        }
    } catch (error: Exception) {
        "दृश्य विश्लेषण विफल: ${error.message ?: "अनजान त्रुटि"}"
    } finally {
        file.delete()
    }
}
