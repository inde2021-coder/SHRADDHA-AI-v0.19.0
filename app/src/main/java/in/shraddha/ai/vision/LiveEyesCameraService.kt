package `in`.shraddha.ai.vision

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.Lia.assistant.data.VoiceGatewaySettings
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.voice.VoiceSessionManager
import `in`.shraddha.ai.link.DeviceLinkManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-started, headless camera-as-eyes session. No preview surface is created.
 * Android's camera privacy indicator and foreground notification remain visible.
 */
class LiveEyesCameraService : Service() {
    private var worker: HandlerThread? = null
    private var camera: CameraDevice? = null
    private var reader: ImageReader? = null
    private var lastAnalysisAt = 0L
    private val requestInFlight = AtomicBoolean(false)
    @Volatile private var previousObservation = ""
    @Volatile private var stopped = false
    @Volatile private var lastAnnouncedAt = 0L
    private var lastCloudAnalysisAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            publishState(STATE_OFF)
            stopSelf()
            return START_NOT_STICKY
        }
        // A null, stale or unknown service intent must never turn on the camera.
        if (intent?.action != ACTION_START) {
            publishState(STATE_OFF)
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 14+ can reject a camera foreground-service start when CAMERA
        // permission is absent. Check before startForeground so revocation fails closed
        // without crashing the process or briefly advertising an active camera session.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            publishState(STATE_OFF)
            stopSelf()
            return START_NOT_STICKY
        }
        stopped = false
        publishState(STATE_STARTING)
        val assistantName = assistantName()
        startForeground(NOTIFICATION_ID, notification("$assistantName Eyes चालू हैं"))
        val cloudEnabled = getSharedPreferences(AMBIENT_PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_CLOUD_ANALYSIS_ENABLED, false)
        val config = VoiceGatewaySettings.get(this)
        val backendReady = config.readError == null && config.baseUrl.startsWith("https://") && config.gatewayToken.isNotBlank()
        // A missing cloud gateway must never disable the local-first camera path.
        // Cloud frames are independently gated below; local OCR/object tracking remains active.
        closeCamera()
        previousObservation = ""
        lastAnnouncedAt = 0L
        lastCloudAnalysisAt = 0L
        updateNotification(
            if (cloudEnabled && backendReady) "Live Eyes active • local + consented cloud understanding"
            else if (cloudEnabled) "Live Eyes active • local-only (secure cloud backend unavailable)"
            else "Live Eyes active • on-device OCR and object tracking only"
        )
        openHeadlessCamera(if (backendReady) config.baseUrl else "", if (backendReady) config.gatewayToken else "")
        return START_NOT_STICKY
    }

    private fun notification(message: String): Notification {
        val channelId = "shraddha_live_eyes"
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channelId, "${assistantName()} Eyes", NotificationManager.IMPORTANCE_LOW))
        }
        val stopIntent = Intent(this, LiveEyesCameraService::class.java).setAction(ACTION_STOP)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val stopPendingIntent = PendingIntent.getService(this, 74, stopIntent, pendingFlags)
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("${assistantName()} Eyes")
            .setContentText(message).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopPendingIntent).build()
    }

    private fun assistantName(): String =
        NovaPreferences.getString(this, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.NAME)
            .trim().ifBlank { AssistantBrand.NAME }

    private fun visionProvider(): String {
        val selected = NovaPreferences.getString(this, NovaPreferences.Keys.AI_PROVIDER, "GEMINI")
            .trim().uppercase()
        return when (selected) {
            "GEMINI" -> "gemini"
            "OPENAI" -> "openai"
            "CLAUDE", "ANTHROPIC" -> "anthropic"
            // Groq and Perplexity are not currently exposed by /api/vision.
            // Use the backend's configured automatic failover instead of silently misrouting.
            "AUTO", "GROQ", "PERPLEXITY" -> "auto"
            else -> "auto"
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun openHeadlessCamera(baseUrl: String, token: String) {
        worker = HandlerThread("ShraddhaLiveEyes").also { it.start() }
        val handler = Handler(worker!!.looper)
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull() ?: run { stopSelf(); return }
            val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG).orEmpty()
            val size = jpegSizes.filter { it.width <= 640 && it.height <= 480 }
                .maxByOrNull { it.width * it.height } ?: jpegSizes.minByOrNull { it.width * it.height }
                ?: run { stopSelf(); return }
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            reader?.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        stopSelf()
                        return@setOnImageAvailableListener
                    }
                    if (now - lastAnalysisAt < ANALYSIS_INTERVAL_MS || !requestInFlight.compareAndSet(false, true)) return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val jpeg = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    if (jpeg.isEmpty() || jpeg.size > MAX_FRAME_BYTES) {
                        jpeg.fill(0); requestInFlight.set(false); return@setOnImageAvailableListener
                    }
                    lastAnalysisAt = now
                    // Local OCR/object tracking stays on-device; cloud scene understanding is rate-limited.
                    // The camera frame is held only for this request and zeroed after dispatch.
                    LiveFrameIntelligence.analyzeJpeg(jpeg) { localResult ->
                        if (!stopped) localResult.onSuccess { local ->
                            val localContext = local.toContext()
                            if (localContext.isNotBlank()) {
                                VoiceSessionManager.updateLiveVisualContext(localContext)
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREF_OBSERVATION, localContext).apply()
                                updateNotification("Live environment awareness active • local objects/text detected")
                            }
                        }
                    }
                    val cloudConsent = getSharedPreferences(AMBIENT_PREFS, Context.MODE_PRIVATE)
                        .getBoolean(PREF_CLOUD_ANALYSIS_ENABLED, false)
                    val backendReadyNow = baseUrl.startsWith("https://") && token.isNotBlank()
                    if (!AmbientIntelligencePolicy.mayAnalyzeCloudFrame(
                            cameraPermissionGranted = true,
                            cloudAnalysisConsentEnabled = cloudConsent,
                            secureBackendReady = backendReadyNow,
                            nowMs = now,
                            lastAnalysisMs = lastCloudAnalysisAt
                        )) {
                        jpeg.fill(0)
                        requestInFlight.set(false)
                        return@setOnImageAvailableListener
                    }
                    lastCloudAnalysisAt = now
                    val prompt = AmbientIntelligencePolicy.buildScenePrompt(
                        assistantName(),
                        previousObservation.takeLast(900)
                    )
                    DeviceLinkManager.analyzeScreen(baseUrl, token, visionProvider(), prompt, jpeg, "general") { result ->
                        jpeg.fill(0)
                        if (!stopped) result.onSuccess { raw ->
                            val observation = AmbientIntelligencePolicy.cleanObservation(raw)
                            val previous = previousObservation
                            val changed = observation.isNotBlank() && observation != previous
                            if (changed) {
                                previousObservation = observation
                                VoiceSessionManager.updateLiveVisualContext(observation)
                            }
                            updateNotification("Ambient scene understanding active • latest context refreshed")
                            val completedAt = android.os.SystemClock.elapsedRealtime()
                            val companionEnabled = getSharedPreferences(AMBIENT_PREFS, Context.MODE_PRIVATE)
                                .getBoolean(PREF_PROACTIVE_COMPANION, true)
                            if (companionEnabled && AmbientIntelligencePolicy.shouldAnnounce(
                                    observation, previous, completedAt, lastAnnouncedAt
                                )) {
                                lastAnnouncedAt = completedAt
                                // Speech is only emitted into an already user-started voice session.
                                // This camera service never starts or activates the microphone.
                                VoiceSessionManager.announceVisualObservation(
                                    this@LiveEyesCameraService,
                                    observation
                                )
                            }
                        }.onFailure { error ->
                            updateNotification("दृश्य विश्लेषण उपलब्ध नहीं: " + (error.message ?: "नेटवर्क त्रुटि").take(90))
                        }
                        requestInFlight.set(false)
                    }
                } catch (_: Exception) {
                    requestInFlight.set(false)
                } finally {
                    image.close()
                }
            }, handler)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    val surface = reader?.surface ?: run { stopSelf(); return }
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        }.build()
                        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (camera !== device) { session.close(); return }
                                runCatching { session.setRepeatingRequest(request, null, handler) }
                                    .onSuccess { publishState(STATE_ACTIVE) }
                                    .onFailure { stopSelf() }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { stopSelf() }
                        }, handler)
                    } catch (_: Exception) { stopSelf() }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (camera === device) camera = null; stopSelf() }
                override fun onError(device: CameraDevice, error: Int) { device.close(); if (camera === device) camera = null; stopSelf() }
            }, handler)
        } catch (_: SecurityException) { stopSelf() }
        catch (_: Exception) { stopSelf() }
    }

    private fun closeCamera() {
        runCatching { camera?.close() }; camera = null
        runCatching { reader?.close() }; reader = null
        worker?.quitSafely(); worker = null
        requestInFlight.set(false)
    }

    override fun onDestroy() {
        stopped = true
        publishState(STATE_OFF)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(PREF_OBSERVATION).apply()
        closeCamera()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun publishState(state: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_STATE, state)
            .putInt(PREF_PID, android.os.Process.myPid())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "shraddha_live_eyes_state"
        const val AMBIENT_PREFS = "shraddha_ambient_settings"
        const val PREF_PROACTIVE_COMPANION = "proactive_companion_enabled"
        const val PREF_CLOUD_ANALYSIS_ENABLED = "cloud_scene_analysis_consent"
        private const val PREF_STATE = "state"
        private const val PREF_PID = "pid"
        private const val PREF_OBSERVATION = "latest_observation"
        const val STATE_OFF = "OFF"
        const val STATE_STARTING = "STARTING"
        const val STATE_ACTIVE = "ACTIVE"

        fun getLatestObservation(context: Context): String = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_OBSERVATION, "").orEmpty()

        fun getState(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getInt(PREF_PID, -1) != android.os.Process.myPid()) return STATE_OFF
            return prefs.getString(PREF_STATE, STATE_OFF) ?: STATE_OFF
        }

        const val ACTION_START = "in.shraddha.ai.action.LIVE_EYES_START"
        const val ACTION_STOP = "in.shraddha.ai.action.LIVE_EYES_STOP"
        private const val NOTIFICATION_ID = 73
        private const val ANALYSIS_INTERVAL_MS = 2800L
        private const val MAX_FRAME_BYTES = 2_500_000
    }
}
