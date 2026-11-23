package com.Lia.assistant.ui.screens.vision

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.ar.core.ArCoreApk
import com.Lia.assistant.ui.components.NovaButton
import com.Lia.assistant.ui.components.NovaButtonType
import com.Lia.assistant.ui.components.NovaGlassCard
import com.Lia.assistant.ui.components.NovaTopBar
import com.Lia.assistant.ui.theme.LocalNovaColors
import com.Lia.assistant.ui.theme.nightSky
import `in`.shraddha.ai.vision.ArWorldMappingEngine
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Composable
fun ArMappingScreen(navController: NavController) {
    val context = LocalContext.current
    val colors = LocalNovaColors.current
    val activity = context as? Activity
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var installRequested by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<GLSurfaceView?>(null) }
    var engine by remember { mutableStateOf<ArWorldMappingEngine?>(null) }
    var status by remember { mutableStateOf("ARCore availability: checking…") }
    var stats by remember { mutableStateOf("No AR tracking session.") }
    var survey by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        status = if (granted) "Camera permission granted. Tap Start Mapping." else "Camera permission denied; AR mapping cannot start."
    }

    LaunchedEffect(context) {
        status = runCatching { ArCoreApk.getInstance().checkAvailability(context).toString() }.getOrElse { "ARCore unavailable: ${it.message}" }
    }

    fun startMapping() {
        val host = activity ?: run { status = "AR mapping requires an Android Activity."; return }
        if (!cameraGranted) { permissionLauncher.launch(Manifest.permission.CAMERA); return }
        try {
            when (ArCoreApk.getInstance().requestInstall(host, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    status = "Install/update ARCore, then tap Start Mapping again."
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            val mapper = ArWorldMappingEngine(context)
            engine?.close()
            engine = mapper
            running = true
            status = "ARCore starting. Move the phone slowly across the room."
        } catch (error: Exception) {
            status = "ARCore could not start: ${error.localizedMessage ?: "unknown error"}"
            running = false
        }
    }

    fun stopMapping() {
        running = false
        surface?.onPause()
        survey = runCatching { engine?.exportSurveyJson().orEmpty() }.getOrDefault("")
        engine?.close()
        engine = null
        stats = "Mapping stopped. Survey contains sparse pose, plane and depth metadata."
        status = "Mapping stopped."
    }

    DisposableEffect(engine) {
        onDispose {
            surface?.onPause()
            engine?.close()
        }
    }

    Column(
        Modifier.fillMaxSize().nightSky().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NovaTopBar(title = "AR WORLD MAPPING", navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
        })
        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ARCore world tracking + depth survey", color = colors.textPrimary)
                Text(status, color = colors.textSecondary)
                Text(stats, color = colors.textSecondary)
                Text("Depth is enabled only when this device supports ARCore Depth. Move slowly and keep textured surfaces in view.", color = colors.textSecondary)
            }
        }
        if (running) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                factory = { viewContext ->
                    GLSurfaceView(viewContext).apply {
                        setEGLContextClientVersion(2)
                        preserveEGLContextOnPause = true
                        val activeEngine = requireNotNull(engine)
                        setRenderer(ArSurveyRenderer(activeEngine) { sample ->
                            stats = sample
                        })
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        surface = this
                    }
                },
                update = { surface = it }
            )
        }
        NovaGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NovaButton(
                    text = if (running) "STOP & SAVE SURVEY" else if (cameraGranted) "START MAPPING" else "ALLOW CAMERA",
                    onClick = {
                        if (running) stopMapping() else startMapping()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    type = if (running) NovaButtonType.SECONDARY else NovaButtonType.PRIMARY
                )
                if (survey.isNotBlank()) {
                    NovaButton(
                        text = "COPY SURVEY JSON",
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Shraddha AR survey", survey))
                            status = "Survey JSON copied to clipboard."
                        },
                        modifier = Modifier.fillMaxWidth(),
                        type = NovaButtonType.SECONDARY
                    )
                }
            }
        }
        Text("This screen records ARCore camera pose, detected planes, point-cloud counts and supported depth ranges. It does not create a dense textured 3D mesh or CAD-grade scan.", color = colors.textSecondary)
    }
}

private class ArSurveyRenderer(
    private val engine: ArWorldMappingEngine,
    private val onSample: (String) -> Unit
) : GLSurfaceView.Renderer {
    private var textureId = 0
    private var lastUiUpdate = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.025f, 0.035f, 0.06f, 1f)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        runCatching { engine.start(textureId) }.onFailure { onSample("ARCore session error: ${it.message}") }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val sample = engine.update() ?: return
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate >= 750L) {
            lastUiUpdate = now
            onSample(
                "Tracking: ${if (sample.tracking) "ACTIVE" else "SEARCHING"} • Planes: ${sample.trackedPlanes.size} • Points: ${sample.pointCount} • Depth: ${if (sample.depthSupported) "ON" else "UNSUPPORTED"} • Valid depth pixels: ${sample.validDepthSamples}" +
                    (sample.minDepthMm?.let { " • Range: ${it}–${sample.maxDepthMm} mm" } ?: "")
            )
        }
    }
}
