package com.Lia.assistant.voice


import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.Lia.assistant.MainActivity
import `in`.shraddha.ai.R
import com.Lia.assistant.data.AssistantBrand
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.ui.fx.OverlayEdgeGlowController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VoiceForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.Lia.assistant.action.START_VOICE"
        const val ACTION_STOP = "com.Lia.assistant.action.STOP_VOICE"
        private const val CHANNEL_ID = "lia_voice_channel"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_EXPECTED_SESSION_EPOCH = "expected_session_epoch"

        fun start(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context, expectedSessionEpoch: Long? = null) {
            val intent = Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_STOP
                expectedSessionEpoch?.let { putExtra(EXTRA_EXPECTED_SESSION_EPOCH, it) }
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                val expectedEpoch = intent.getLongExtra(EXTRA_EXPECTED_SESSION_EPOCH, -1L)
                if (expectedEpoch >= 0L && !VoiceSessionManager.isCurrentSession(expectedEpoch)) {
                    // An old connection failure must not stop a newer user-started session.
                    return START_NOT_STICKY
                }
                if (VoiceSessionManager.isActiveNow()) VoiceSessionManager.stop()
                OverlayEdgeGlowController.stop(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf(startId)
                START_NOT_STICKY
            }
            ACTION_START -> {
                // Permission can be revoked in Android Settings while the app is backgrounded.
                // Never activate the microphone unless the current runtime grant is still valid.
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    VoiceSessionManager.stop()
                    OverlayEdgeGlowController.stop(this)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                createNotificationChannel()
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                OverlayEdgeGlowController.start(this)
                VoiceSessionManager.start(this)
                // A killed service must not silently restart microphone capture.
                START_NOT_STICKY
            }
            else -> {
                // Null, stale and unknown intents must never activate the microphone.
                VoiceSessionManager.stop()
                OverlayEdgeGlowController.stop(this)
                stopSelf(startId)
                START_NOT_STICKY
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val assistantName = NovaPreferences.getString(this, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.NAME)
            val channel = NotificationChannel(CHANNEL_ID, "$assistantName voice session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Visible, user-started voice session for $assistantName"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val assistantName = NovaPreferences.getString(this, NovaPreferences.Keys.ASSISTANT_NAME, AssistantBrand.NAME)
        val openAppIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, VoiceForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(assistantName)
            .setContentText("Voice session started • microphone opens only after secure connection")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(0, "Stop microphone", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        if (VoiceSessionManager.isActiveNow()) VoiceSessionManager.stop()
        OverlayEdgeGlowController.stop(this)
        super.onDestroy()
    }
}
