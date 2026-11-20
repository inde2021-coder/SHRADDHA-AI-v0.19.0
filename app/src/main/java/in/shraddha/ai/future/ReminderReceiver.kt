package `in`.shraddha.ai.future

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

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

        val preferences = context.getSharedPreferences("shraddha_task_planner", Context.MODE_PRIVATE)
        if (preferences.getBoolean("daily_briefing_enabled", false)) DailyBriefingReceiver.scheduleNext(context)
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


/** Posts a concise, local daily plan and schedules the next morning automatically. */
class DailyBriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("shraddha_task_planner", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("daily_briefing_enabled", false)) return
        val now = Calendar.getInstance()
        val today = mutableListOf<String>()
        val overdue = mutableListOf<String>()
        runCatching {
            val tasks = org.json.JSONArray(prefs.getString("tasks", "[]") ?: "[]")
            for (i in 0 until tasks.length()) {
                val task = tasks.getJSONObject(i)
                if (task.optBoolean("done")) continue
                val dueAt = task.optLong("dueAt")
                val due = Calendar.getInstance().apply { timeInMillis = dueAt }
                val isToday = due.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    due.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
                if (isToday) today.add(task.optString("title").take(70))
                else if (dueAt < now.timeInMillis) overdue.add(task.optString("title").take(70))
            }
        }
        val lines = mutableListOf<String>()
        if (overdue.isNotEmpty()) lines.add("Overdue: " + overdue.take(3).joinToString(", "))
        if (today.isNotEmpty()) lines.add("Today: " + today.take(4).joinToString(", "))
        if (lines.isEmpty()) lines.add("No pending tasks due today. Take a moment to plan your priorities.")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("shraddha_daily_briefing", "Daily briefing", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val body = lines.joinToString("\n")
        val appName = prefs.getString("assistant_name", "Shraddha AI")?.take(60) ?: "Shraddha AI"
        val notification = NotificationCompat.Builder(context, "shraddha_daily_briefing")
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("$appName • Good morning")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        manager.notify(81008, notification)
        scheduleNext(context)
    }

    companion object {
        private const val REQUEST_CODE = 81008
        fun scheduleNext(context: Context) {
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            val pending = PendingIntent.getBroadcast(
                context, REQUEST_CODE, Intent(context, DailyBriefingReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
        }
    }
}
