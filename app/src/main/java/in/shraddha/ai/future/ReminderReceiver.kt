package `in`.shraddha.ai.future

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title")?.take(100)?.ifBlank { "Shraddha AI reminder" } ?: "Shraddha AI reminder"
        val note = intent.getStringExtra("note")?.take(500).orEmpty()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("shraddha_reminders", "Shraddha reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(context, "shraddha_reminders")
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title).setContentText(note).setStyle(NotificationCompat.BigTextStyle().bigText(note))
            .setAutoCancel(true).build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
