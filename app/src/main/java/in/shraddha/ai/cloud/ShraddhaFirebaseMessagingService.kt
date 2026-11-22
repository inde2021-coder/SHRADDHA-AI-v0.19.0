package `in`.shraddha.ai.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.Lia.assistant.data.NovaPreferences
import com.Lia.assistant.data.AssistantBrand
import com.google.firebase.messaging.RemoteMessage
import `in`.shraddha.ai.MainActivity
import `in`.shraddha.ai.brain.EncryptedFeatureStore

/** Stores FCM tokens encrypted locally and displays only generic, non-sensitive event notices. */
class ShraddhaFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (token.isNotBlank() && token.length <= 4096) {
            val store = EncryptedFeatureStore(applicationContext)
            if (store.get("firebase_fcm_consent").getOrNull() != "true" ||
                store.get("firebase_push_consent").getOrNull() != "true") return
            val previous = store.get("firebase_fcm_token").getOrNull().orEmpty()
            if (previous.isNotBlank() && previous != token) {
                store.put("firebase_fcm_previous_token", previous)
            }
            store.put("firebase_fcm_token", token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val store = EncryptedFeatureStore(applicationContext)
        if (store.get("firebase_fcm_consent").getOrNull() != "true" ||
            store.get("firebase_push_consent").getOrNull() != "true") return
        val type = message.data["type"].orEmpty()
        val safeMessage = when (type) {
            "device_paired" -> "A device was paired with your $assistantName account."
            "task_handoff" -> "A task handoff is waiting for your review."
            "security_alert" -> "A security event needs your attention."
            "backup_ready" -> "Your encrypted recovery backup has an update."
            else -> "You have a new $assistantName update."
        }
        showNotification(safeMessage)
    }

    private fun showNotification(message: String) {
        val channelId = "shraddha_updates"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(channelId) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "$assistantName updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 7102, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(assistantName)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        }
    }
}
