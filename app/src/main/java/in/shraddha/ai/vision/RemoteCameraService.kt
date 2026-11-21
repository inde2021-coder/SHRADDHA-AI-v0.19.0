package `in`.shraddha.ai.vision

import android.Manifest
import android.app.*
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
import `in`.shraddha.ai.link.DeviceLinkManager

/** Explicit, user-started camera sharing. Android camera privacy indicator and ongoing notification remain visible. */
class RemoteCameraService : Service() {
    private var thread: HandlerThread? = null
    private var camera: CameraDevice? = null
    private var reader: ImageReader? = null
    private var lastFrameAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotification()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        stopCamera()
        startCamera(intent?.getStringExtra(EXTRA_FACING) ?: "back", intent?.getStringExtra(EXTRA_TARGET).orEmpty())
        return START_NOT_STICKY
    }

    private fun createNotification() {
        val channelId = "shraddha_camera_share"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Shraddha remote camera", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Shraddha AI camera sharing is active")
            .setContentText("Your camera is being shared with paired devices. Stop it in Shraddha AI.")
            .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()
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
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastFrameAt < 250) { source.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                    DeviceLinkManager.sendFrame(bytes, 2, targetDeviceId)
                    lastFrameAt = now
                } catch (_: Exception) { }
                finally { image.close() }
            }, handler)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    val surface = reader?.surface ?: run { stopSelf(); return }
                    try {
                        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
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
                        }, handler)
                    } catch (_: Exception) { stopSelf() }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); if (camera === device) camera = null; stopSelf() }
                override fun onError(device: CameraDevice, error: Int) { device.close(); if (camera === device) camera = null; stopSelf() }
            }, handler)
        } catch (_: SecurityException) { stopSelf() }
        catch (_: Exception) { stopSelf() }
    }

    private fun stopCamera() {
        try { camera?.close() } catch (_: Exception) { }
        camera = null
        reader?.close(); reader = null
        thread?.quitSafely(); thread = null
    }

    override fun onDestroy() {
        stopCamera()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val EXTRA_FACING = "cameraFacing"; const val EXTRA_TARGET = "targetDeviceId" }
}
