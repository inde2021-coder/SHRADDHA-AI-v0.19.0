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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            publishState(STATE_OFF)
            stopSelf()
            return START_NOT_STICKY
        }
        stopped = false
        publishState(STATE_STARTING)
        val assistantName = assistantName()
        startForeground(NOTIFICATION_ID, notification("$assistantName Eyes चालू हैं"))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        val config = VoiceGatewaySettings.get(this)
        if (!config.baseUrl.startsWith("https://") || config.gatewayToken.isBlank()) {
            updateNotification("पहले Settings में सुरक्षित AI backend जोड़ें")
            stopSelf()
            return START_NOT_STICKY
        }
        closeCamera()
        previousObservation = ""
        lastAnnouncedAt = 0L
        openHeadlessCamera(config.baseUrl, config.gatewayToken)
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
                    if (now - lastAnalysisAt < ANALYSIS_INTERVAL_MS || !requestInFlight.compareAndSet(false, true)) return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val jpeg = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    if (jpeg.isEmpty() || jpeg.size > MAX_FRAME_BYTES) {
                        jpeg.fill(0); requestInFlight.set(false); return@setOnImageAvailableListener
                    }
                    lastAnalysisAt = now
                    val context = previousObservation.takeLast(900)
                    val prompt = buildString {
                        append("You are " + assistantName() + "'s live visual eyes. Describe only what is clearly visible in this current camera view. ")
                        append("Identify useful objects, people only by visible non-sensitive context, text/signs, surroundings and safety-relevant changes. ")
                        append("Compare with the previous observation when useful; do not invent movement or details. ")
                        if (context.isNotBlank()) append("Previous observation: ").append(context).append(". ")
                        append("Give a concise natural Hindi response, 1-3 sentences. Current user request: बताओ अभी सामने क्या दिख रहा है और क्या बदला है?")
                    }
                    DeviceLinkManager.analyzeScreen(baseUrl, token, "gemini", prompt, jpeg, "general") { result ->
                        jpeg.fill(0)
                        if (!stopped) result.onSuccess { raw ->
                            val observation = raw.take(1200)
                            val changed = previousObservation.isBlank() || previousObservation != observation
                            previousObservation = observation
                            updateNotification("दृश्य समझ लिया: " + observation.take(110))
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (changed && now - lastAnnouncedAt >= 12_000L) {
                                lastAnnouncedAt = now
                                VoiceSessionManager.updateLiveVisualContext(observation)
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
        private const val PREF_STATE = "state"
        private const val PREF_PID = "pid"
        const val STATE_OFF = "OFF"
        const val STATE_STARTING = "STARTING"
        const val STATE_ACTIVE = "ACTIVE"

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
