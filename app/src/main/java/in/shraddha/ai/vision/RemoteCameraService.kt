package `in`.shraddha.ai.vision

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.content.ContextCompat
import `in`.shraddha.ai.link.DeviceLinkManager
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.AssistantBrand

/** Explicit, user-started camera sharing. Android camera privacy indicator and ongoing notification remain visible. */
class RemoteCameraService : Service() {
    private var thread: HandlerThread? = null
    private var camera: CameraDevice? = null
    private var reader: ImageReader? = null
    private var lastFrameAt = 0L
    @Volatile private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (RemoteCameraCommandPolicy.resolve(intent?.action)) {
            RemoteCameraCommandPolicy.Command.STOP -> {
            stopping = true
            stopCamera()
            getSharedPreferences("shraddha_remote_camera", Context.MODE_PRIVATE).edit().remove("active_target").remove("active_facing").apply()
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
            stopSelfResult(startId)
            return START_NOT_STICKY
            }
            RemoteCameraCommandPolicy.Command.REJECT -> {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            RemoteCameraCommandPolicy.Command.START -> Unit
        }
        stopping = false
        val facing = intent?.getStringExtra(EXTRA_FACING) ?: "back"
        val target = intent?.getStringExtra(EXTRA_TARGET).orEmpty()
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val consentEnabled = getSharedPreferences("shraddha_data_permissions", Context.MODE_PRIVATE).getBoolean("camera", false)
        // Validate current runtime grant and user consent before starting a camera foreground service.
        if (!RemoteCameraPolicy.mayStream(cameraGranted, consentEnabled, target, facing)) {
            stopSelf(); return START_NOT_STICKY
        }
        createNotification()
        stopCamera()
        getSharedPreferences("shraddha_remote_camera", Context.MODE_PRIVATE).edit().putString("active_target", target).putString("active_facing", facing).apply()
        runCatching { `in`.shraddha.ai.future.LocalAuditTrail(applicationContext).append("camera_share_service", "started", "facing=$facing; target_id_redacted") }
        startCamera(facing, target)
        return START_NOT_STICKY
    }

    private fun createNotification() {
        val assistantName = NovaPreferences.getString(this, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.NAME).trim().ifBlank { AssistantBrand.NAME }
        val channelId = "shraddha_camera_share"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "$assistantName remote camera", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, RemoteCameraService::class.java).setAction(ACTION_STOP)
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val stopPendingIntent = PendingIntent.getService(this, 43, stopIntent, pendingFlags)
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("$assistantName camera sharing is active")
            .setContentText("Camera stream is limited to the selected paired device.")
            .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopPendingIntent).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(42, notification)
    }

    private fun startCamera(facing: String, targetDeviceId: String) {
        if (targetDeviceId.isBlank()) { stopSelf(); return }
        thread = HandlerThread("ShraddhaRemoteCamera").also { it.start() }
        val handler = Handler(thread!!.looper)
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val wantedFacing = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = manager.cameraIdList.firstOrNull { cameraId ->
                manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == wantedFacing
            } ?: manager.cameraIdList.firstOrNull() ?: run { stopSelf(); return }
            val chars = manager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(ImageFormat.JPEG)?.filter { it.width <= 640 && it.height <= 480 }?.maxByOrNull { it.width * it.height }
                ?: android.util.Size(640, 480)
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            reader?.setOnImageAvailableListener({ source ->
                if (stopping) { source.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastFrameAt < 250) { source.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    val consentEnabled = getSharedPreferences("shraddha_data_permissions", Context.MODE_PRIVATE).getBoolean("camera", false)
                    if (!RemoteCameraPolicy.mayStream(cameraGranted, consentEnabled, targetDeviceId, facing)) {
                        stopSelf()
                        return@setOnImageAvailableListener
                    }
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                    val sent = try {
                        DeviceLinkManager.sendFrame(bytes, 2, targetDeviceId)
                    } finally {
                        // Camera frames are sensitive. Wipe the temporary copy even if
                        // encryption/relay dispatch throws unexpectedly.
                        bytes.fill(0)
                    }
                    if (!RemoteCameraPolicy.shouldContinueAfterFrame(
                            sent,
                            cameraGranted,
                            consentEnabled,
                            targetDeviceId,
                            facing
                        )) {
                        runCatching { `in`.shraddha.ai.future.LocalAuditTrail(applicationContext).append("camera_share_frame", "relay_unavailable", "camera_stopped_fail_closed") }
                        stopSelf()
                        return@setOnImageAvailableListener
                    }
                    lastFrameAt = now
                } catch (_: Exception) {
                    runCatching { `in`.shraddha.ai.future.LocalAuditTrail(applicationContext).append("camera_share_frame", "failed", "camera_stopped_on_exception") }
                    // Any capture, encoding, encryption, or relay exception fails closed.
                    stopSelf()
                }
                finally { image.close() }
            }, handler)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (stopping) { device.close(); return }
                    camera = device
                    val surface = reader?.surface ?: run { stopSelf(); return }
                    try {
                        val callback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(surface)
                                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    }.build()
                                    session.setRepeatingRequest(request, null, handler)
                                } catch (_: Exception) { stopSelf() }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { stopSelf() }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val configuration = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(OutputConfiguration(surface)),
                                ContextCompat.getMainExecutor(this@RemoteCameraService),
                                callback
                            )
                            device.createCaptureSession(configuration)
                        } else {
                            createLegacyCaptureSession(device, surface, callback, handler)
                        }
                    } catch (_: Exception) { stopSelf() }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (camera === device) camera = null; stopSelf() }
                override fun onError(device: CameraDevice, error: Int) { device.close(); if (camera === device) camera = null; stopSelf() }
            }, handler)
        } catch (_: SecurityException) { stopSelf() }
        catch (_: Exception) { stopSelf() }
    }

    @Suppress("DEPRECATION")
    private fun createLegacyCaptureSession(
        device: CameraDevice,
        surface: android.view.Surface,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler
    ) {
        device.createCaptureSession(listOf(surface), callback, handler)
    }

    private fun stopCamera() {
        try { camera?.close() } catch (_: Exception) { }
        camera = null
        reader?.close(); reader = null
        thread?.quitSafely(); thread = null
    }

    override fun onDestroy() {
        stopping = true
        stopCamera()
        getSharedPreferences("shraddha_remote_camera", Context.MODE_PRIVATE).edit().remove("active_target").remove("active_facing").apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ACTION_START = "in.shraddha.ai.vision.REMOTE_CAMERA_START"
        const val ACTION_STOP = "in.shraddha.ai.vision.REMOTE_CAMERA_STOP"
        const val EXTRA_FACING = "cameraFacing"
        const val EXTRA_TARGET = "targetDeviceId"
    }
}
