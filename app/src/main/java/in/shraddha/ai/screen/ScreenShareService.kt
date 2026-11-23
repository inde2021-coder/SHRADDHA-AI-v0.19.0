package `in`.shraddha.ai.screen

import android.app.*
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import `in`.shraddha.ai.link.DeviceLinkManager
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.AssistantBrand
import java.io.ByteArrayOutputStream

/** Wipes the reusable JPEG compression buffer after each screen frame. */
private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
    fun wipe() {
        buf.fill(0)
        reset()
    }
}

/** User-started screen sharing. Android's MediaProjection consent dialog is required every session. */
class ScreenShareService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var lastFrameAt = 0L
    private var targetDeviceId: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent?.getParcelableExtra(EXTRA_DATA)
        targetDeviceId = intent?.getStringExtra(EXTRA_TARGET_DEVICE_ID).orEmpty()
        if (resultCode != Activity.RESULT_OK || data == null ||
            !targetDeviceId.matches(Regex("[A-Za-z0-9_-]{8,80}"))) {
            // MediaProjection consent is necessary but not sufficient: bind sharing to
            // one valid paired-device identifier before starting a foreground service.
            stopSelf()
            return START_NOT_STICKY
        }
        createNotification()
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val callbackHandler = Handler(Looper.getMainLooper())
            projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, callbackHandler)
            startCapture()
        } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    private fun createNotification() {
        val assistantName = NovaPreferences.getString(this, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.NAME).trim().ifBlank { AssistantBrand.NAME }
        val channelId = "shraddha_screen_share"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "$assistantName live screen sharing", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("$assistantName screen sharing is active")
            .setContentText("Your screen is being shared with the selected paired device. Stop it in $assistantName to end.")
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(41, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(41, notification)
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val scale = minOf(1f, 720f / metrics.widthPixels)
        val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)
        val density = (metrics.densityDpi * scale).toInt().coerceAtLeast(1)
        thread = HandlerThread("ShraddhaScreenCapture").also { it.start() }
        val handler = Handler(thread!!.looper)
        reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ source ->
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastFrameAt < 250) { source.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            var bitmap: Bitmap? = null
            var cropped: Bitmap? = null
            try {
                val plane = image.planes[0]; val buffer = plane.buffer
                val pixelStride = plane.pixelStride; val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val createdBitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap = createdBitmap
                createdBitmap.copyPixelsFromBuffer(buffer)
                val croppedBitmap = Bitmap.createBitmap(createdBitmap, 0, 0, width, height)
                cropped = croppedBitmap
                val out = WipingByteArrayOutputStream()
                try {
                    cropped.compress(Bitmap.CompressFormat.JPEG, 48, out)
                    val frame = out.toByteArray()
                    try {
                        if (frame.isNotEmpty() && frame.size <= 2_500_000) {
                            DeviceLinkManager.sendFrame(frame, 1, targetDeviceId)
                            lastFrameAt = now
                        }
                    } finally {
                        frame.fill(0)
                    }
                } finally {
                    out.wipe()
                    out.close()
                }
                bitmap.recycle(); if (cropped !== bitmap) cropped.recycle()
            } catch (_: Exception) { /* A dropped frame is safer than stopping the share session. */ }
            finally {
                if (cropped != null && cropped !== bitmap && !cropped.isRecycled) cropped.recycle()
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                image.close()
            }
        }, handler)
        display = projection?.createVirtualDisplay("ShraddhaLiveShare", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
            ?: error("Android could not create the screen-sharing display.")
    }

    override fun onDestroy() {
        display?.release(); reader?.close(); projection?.stop(); thread?.quitSafely()
        display = null; reader = null; projection = null; thread = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val EXTRA_RESULT_CODE = "resultCode"; const val EXTRA_DATA = "projectionData"; const val EXTRA_TARGET_DEVICE_ID = "targetDeviceId" }
}
