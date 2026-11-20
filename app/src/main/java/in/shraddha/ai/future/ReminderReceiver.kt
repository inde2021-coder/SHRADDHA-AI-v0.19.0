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


/** Restores pending local reminders after Android restarts and package updates. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val raw = context.getSharedPreferences("shraddha_task_planner", Context.MODE_PRIVATE)
            .getString("tasks", "[]") ?: "[]"
        val now = System.currentTimeMillis()
        runCatching {
            val tasks = org.json.JSONArray(raw)
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            for (i in 0 until tasks.length()) {
                val task = tasks.getJSONObject(i)
                val dueAt = task.optLong("dueAt", 0L)
                if (task.optBoolean("done") || dueAt <= now) continue
                val taskId = task.optLong("id")
                val reminder = Intent(context, ReminderReceiver::class.java).apply {
                    putExtra("title", task.optString("title", "Shraddha AI reminder"))
                    putExtra("note", task.optString("note", "Your scheduled task is due.").ifBlank { "Your scheduled task is due." })
                }
                val pending = PendingIntent.getBroadcast(
                    context, taskId.hashCode(), reminder,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending)
            }
        }
    }
}
