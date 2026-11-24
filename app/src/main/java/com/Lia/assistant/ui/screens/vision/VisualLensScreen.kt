package com.Lia.assistant.ui.screens.vision

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.VoiceGatewaySettings
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import `in`.shraddha.ai.link.DeviceLinkManager
import `in`.shraddha.ai.vision.LiveEyesCameraService
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun VisualLensScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colors = LocalNovaColors.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val assistantName = NovaPreferences.getString(
        context, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.FULL_NAME
    ).trim().ifBlank { AssistantBrand.FULL_NAME }

    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("कैमरे को किसी वस्तु, जगह, लिखावट या समस्या की ओर करें और Shraddha से पूछें।") }
    var busy by remember { mutableStateOf(false) }
    var backgroundEyes by remember { mutableStateOf(LiveEyesCameraService.getState(context) == LiveEyesCameraService.STATE_ACTIVE) }

    val controller = remember(context) {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            setEnabledUseCases(CameraController.PREVIEW or CameraController.IMAGE_CAPTURE)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (!granted) answer = "कैमरा अनुमति नहीं मिली। Android Settings में Shraddha AI के लिए Camera अनुमति दें।"
    }

    DisposableEffect(cameraGranted, backgroundEyes, lifecycleOwner, controller) {
        // Camera2 is exclusive on many devices: release the in-app preview before
        // the headless Live Eyes service opens the same physical camera.
        if (cameraGranted && !backgroundEyes) controller.bindToLifecycle(lifecycleOwner)
        else controller.unbind()
        onDispose { controller.unbind() }
    }
    DisposableEffect(cameraExecutor) {
        onDispose { cameraExecutor.shutdown() }
    }
    LaunchedEffect(context) {
        while (true) {
            backgroundEyes = LiveEyesCameraService.getState(context) == LiveEyesCameraService.STATE_ACTIVE ||
                LiveEyesCameraService.getState(context) == LiveEyesCameraService.STATE_STARTING
            delay(700)
        }
    }

    fun captureAndAsk() {
        if (!cameraGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        val prompt = question.trim().ifBlank { "इस तस्वीर में क्या दिखाई दे रहा है? महत्वपूर्ण वस्तुएँ, लिखावट और उपयोगी जानकारी हिंदी में बताओ।" }
        val config = VoiceGatewaySettings.get(context)
        if (config.readError != null) {
            answer = "AI कनेक्शन सेटिंग पढ़ने में समस्या: " + config.readError
            return
        }
        if (!config.baseUrl.startsWith("https://") || config.gatewayToken.isBlank()) {
            answer = "पहले Settings में सुरक्षित HTTPS AI backend URL और gateway token सेट करें।"
            return
        }
        if (busy) return
        busy = true
        answer = "कैमरा तस्वीर ले रहा है…"
        val file = File(context.cacheDir, "shraddha-visual-lens-${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(file).build()
        controller.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                var sourceBytes: ByteArray? = null
                var bitmap: Bitmap? = null
                try {
                    sourceBytes = file.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
                    val sample = maxOf(1, maxOf(bounds.outWidth / 1280, bounds.outHeight / 1280))
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, options)
                        ?: throw IllegalStateException("तस्वीर पढ़ी नहीं जा सकी")
                    val compressed = ByteArrayOutputStream().use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 78, stream)
                        stream.toByteArray()
                    }
                    bitmap.recycle()
                    bitmap = null
                    sourceBytes.fill(0)
                    sourceBytes = null
                    if (compressed.size > 2_500_000) {
                        compressed.fill(0)
                        throw IllegalStateException("तस्वीर का आकार सुरक्षित सीमा से बड़ा है।")
                    }
                    val provider = NovaPreferences.getString(context, NovaPreferences.Keys.AI_PROVIDER, "GEMINI")
                    DeviceLinkManager.analyzeScreen(
                        config.baseUrl, config.gatewayToken, provider, prompt, compressed, "general"
                    ) { result ->
                        compressed.fill(0)
                        mainHandler.post {
                            answer = result.fold(
                                onSuccess = { it },
                                onFailure = { "AI से दृश्य समझने में समस्या: ${it.message ?: "नेटवर्क या provider त्रुटि"}" }
                            )
                            busy = false
                        }
                    }
                } catch (error: Exception) {
                    sourceBytes?.fill(0)
                    bitmap?.recycle()
                    mainHandler.post {
                        answer = "तस्वीर का विश्लेषण शुरू नहीं हो पाया: ${error.message ?: "अनजान त्रुटि"}"
                        busy = false
                    }
                } finally {
                    file.delete()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                mainHandler.post {
                    answer = "कैमरा तस्वीर नहीं ले पाया: ${exception.message ?: "कैमरा त्रुटि"}"
                    busy = false
                }
            }
        })
    }

    Column(
        Modifier.fillMaxSize().nightSky().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NovaTopBar(title = "SHRADDHA VISUAL LENS", navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
        })

        Box(
            Modifier.fillMaxWidth().weight(0.9f, fill = true)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF101522)),
            contentAlignment = Alignment.Center
        ) {
            if (cameraGranted) {
                AndroidView(
                    factory = { viewContext ->
                        PreviewView(viewContext).apply {
                            this.controller = controller
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                    },
                    update = { it.controller = controller },
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    "LIVE CAMERA • AR NOT REQUIRED",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(14.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color(0x99090D16)).padding(horizontal = 10.dp, vertical = 6.dp)
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color(0xFFBDA4FF))
                    Text("Visual Lens को कैमरा अनुमति चाहिए", color = Color.White)
                    Text("यह सामान्य कैमरा + AI पर चलता है। ARCore जरूरी नहीं है।", color = Color(0xFFB8C0D0))
                }
            }
        }

        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            placeholder = { Text("पूछें: यह क्या है? इसे कैसे ठीक करूँ?") },
        )

        Text(
            answer,
            color = colors.textPrimary,
            modifier = Modifier.fillMaxWidth().weight(0.55f, fill = true)
                .clip(RoundedCornerShape(20.dp)).background(Color(0x661B2030)).padding(14.dp)
        )

        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Color(0x661B2030)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Live Eyes • Background", color = colors.textPrimary)
                Text("स्क्रीन बंद होने पर भी दृश्य जागरूकता; Android notification और camera indicator चालू रहेंगे।", color = colors.textSecondary)
            }
            Switch(
                checked = backgroundEyes,
                onCheckedChange = { enabled ->
                    if (enabled && !cameraGranted) {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    } else if (enabled) {
                        val config = VoiceGatewaySettings.get(context)
                        if (config.readError != null || !config.baseUrl.startsWith("https://") || config.gatewayToken.isBlank()) {
                            answer = "Background Live Eyes के लिए पहले सुरक्षित HTTPS AI backend और gateway token सेट करें।"
                        } else {
                            // Trigger unbind/release of PreviewView before starting the headless camera service.
                            backgroundEyes = true
                            runCatching {
                                startForegroundService(context, Intent(context, LiveEyesCameraService::class.java).setAction(LiveEyesCameraService.ACTION_START))
                            }.onFailure {
                                backgroundEyes = false
                                answer = "Live Eyes शुरू नहीं हो पाईं: " + (it.message ?: "अनजान त्रुटि")
                            }
                        }
                    } else {
                        backgroundEyes = false
                        runCatching {
                            context.startService(Intent(context, LiveEyesCameraService::class.java).setAction(LiveEyesCameraService.ACTION_STOP))
                        }.onFailure { answer = "Live Eyes बंद नहीं हो पाईं: " + (it.message ?: "अनजान त्रुटि") }
                    }
                }
            )
        }

        if (!cameraGranted) {
            androidx.compose.material3.Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("कैमरा अनुमति दें") }
        } else {
            androidx.compose.material3.Button(
                onClick = { captureAndAsk() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "विश्लेषण हो रहा है…" else "तस्वीर लेकर Shraddha से पूछें")
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
