package com.Lia.assistant.voice


import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
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

    private var voiceWakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inactiveMonitor: Job? = null
    @Volatile private var stopping = false

    companion object {
        const val ACTION_START = "com.Lia.assistant.action.START_VOICE"
        const val ACTION_STOP = "com.Lia.assistant.action.STOP_VOICE"
        private const val CHANNEL_ID = "lia_voice_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            inactiveMonitor?.cancel()
            VoiceSessionManager.stop()
            releaseVoiceWakeLock()
            OverlayEdgeGlowController.stop(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        stopping = false
        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireVoiceWakeLock()
        OverlayEdgeGlowController.start(this)
        VoiceSessionManager.start(this)

        // If the API key is missing or AudioRecord fails, the manager marks the
        // session inactive. Tear down the foreground service and wake lock too;
        // never leave a phantom "Listening" notification running without a mic.
        inactiveMonitor?.cancel()
        inactiveMonitor = serviceScope.launch {
            VoiceSessionManager.isActive.first { active -> !active }
            if (!stopping) {
                stopping = true
                releaseVoiceWakeLock()
                OverlayEdgeGlowController.stop(this@VoiceForegroundService)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }

        // Android may kill a microphone FGS while the app is backgrounded. Do not
        // resurrect a user-visible microphone session silently after process death;
        // the user must explicitly activate it again from the app.
        return START_NOT_STICKY
    }

    private fun acquireVoiceWakeLock() {
        if (voiceWakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        voiceWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:VoiceSession").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseVoiceWakeLock() {
        runCatching { if (voiceWakeLock?.isHeld == true) voiceWakeLock?.release() }
        voiceWakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val assistantName = NovaPreferences.getString(
                this,
                NovaPreferences.Keys.ASSISTANT_NAME,
                AssistantBrand.NAME
            )
            val channelName = "$assistantName voice session"
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing active voice conversation with $assistantName"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val assistantName = NovaPreferences.getString(
            this,
            NovaPreferences.Keys.ASSISTANT_NAME,
            AssistantBrand.NAME
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, VoiceForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(assistantName)
            .setContentText("Voice session active • higher battery use • Tap Stop to end")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopping = true
        inactiveMonitor?.cancel()
        inactiveMonitor = null
        serviceScope.cancel()
        VoiceSessionManager.stop()
        releaseVoiceWakeLock()
        OverlayEdgeGlowController.stop(this)
        super.onDestroy()
    }
}
