package `in`.shraddha.ai.future

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar

/** Local-first task planner. Reminders are scheduled on-device and need no cloud account. */
class TaskPlannerActivity : AppCompatActivity() {
    private data class Task(val id: Long, val title: String, val note: String, val dueAt: Long, val done: Boolean)
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var briefingToggle: android.widget.Switch
    private val prefs by lazy { getSharedPreferences("shraddha_task_planner", MODE_PRIVATE) }
    private val tasks = mutableListOf<Task>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadTasks()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(24))
            setBackgroundColor(Color.rgb(7, 11, 20))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "TASK PLANNER"; textSize = 21f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        top.addView(Button(this).apply { text = "＋ Add task"; isAllCaps = false; setOnClickListener { createTask() } })
        root.addView(top)
        summary = TextView(this).apply {
            textSize = 14f; setTextColor(Color.rgb(185, 202, 230))
            setPadding(dp(14), dp(14), dp(14), dp(14)); background = panel()
        }
        root.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(14) })
        briefingToggle = android.widget.Switch(this).apply {
            text = "Daily briefing • around 8:00 AM"
            setTextColor(0xFFE4E9F5.toInt())
            isChecked = prefs.getBoolean("daily_briefing_enabled", false)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("daily_briefing_enabled", enabled).apply()
                if (enabled) {
                    scheduleDailyBriefing()
                    toast("Daily briefing scheduled around 8:00 AM")
                } else {
                    cancelDailyBriefing()
                    toast("Daily briefing disabled")
                }
            }
        }
        root.addView(briefingToggle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        root.addView(TextView(this).apply {
            text = "YOUR TASKS"; textSize = 11f; letterSpacing = .12f
            setTextColor(Color.rgb(159, 139, 255)); setPadding(dp(2), dp(8), 0, dp(8))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "← Back to Shraddha AI"; isAllCaps = false; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        setContentView(root)
        render()
    }

    private fun createTask() {
        val input = EditText(this).apply { hint = "What do you need to do?"; setTextColor(Color.WHITE); setHintTextColor(0xFF91A0BA.toInt()) }
        val note = EditText(this).apply { hint = "Optional details"; setTextColor(Color.WHITE); setHintTextColor(0xFF91A0BA.toInt()) }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(input); addView(note) }
        AlertDialog.Builder(this).setTitle("Create a task").setView(form).setNegativeButton("Cancel", null)
            .setPositiveButton("Choose reminder") { _, _ ->
                val title = input.text.toString().trim()
                if (title.isBlank()) { toast("Task title is required"); return@setPositiveButton }
                chooseDueTime(title, note.text.toString().trim())
            }.show()
    }

    private fun chooseDueTime(title: String, note: String) {
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val due = Calendar.getInstance().apply { set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                if (due <= System.currentTimeMillis()) { toast("Choose a future reminder time"); return@TimePickerDialog }
                val task = Task(System.currentTimeMillis(), title, note, due, false)
                tasks.add(task); saveTasks(); schedule(task); render(); toast("Task saved and reminder scheduled")
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun schedule(task: Task) {
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra("title", task.title); putExtra("note", task.note.ifBlank { "Your scheduled task is due." })
        }
        val pending = PendingIntent.getBroadcast(this, task.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.dueAt, pending)
    }

    private fun cancelReminder(task: Task) {
        val pending = PendingIntent.getBroadcast(this, task.id.hashCode(), Intent(this, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending); pending.cancel()
    }

    private fun render() {
        list.removeAllViews()
        val now = System.currentTimeMillis()
        val openTasks = tasks.filter { !it.done }
        val overdue = openTasks.count { it.dueAt < now }
        val dueToday = openTasks.count {
            val a = Calendar.getInstance().apply { timeInMillis = it.dueAt }
            val b = Calendar.getInstance()
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
        summary.text = "${openTasks.size} open   •   $dueToday due today   •   $overdue overdue\n\n" +
            if (openTasks.isEmpty()) "You are all caught up. Add a task to plan your day."
            else "Next: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(openTasks.minBy { it.dueAt }.dueAt)
        if (tasks.isEmpty()) list.addView(TextView(this).apply {
            text = "No tasks yet. Add your first task and set a reminder."; textSize = 14f
            setTextColor(0xFFAFBDD4.toInt()); gravity = Gravity.CENTER; setPadding(dp(18), dp(40), dp(18), dp(40))
        })
        tasks.sortedWith(compareBy<Task> { it.done }.thenBy { it.dueAt }).forEach { task ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = panel() }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = task.title; textSize = 15f; setTextColor(if (task.done) 0xFF8190A8.toInt() else Color.WHITE)
                if (task.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(Button(this).apply {
                text = if (task.done) "Undo" else "Done"; isAllCaps = false
                setOnClickListener {
                    val updated = task.copy(done = !task.done)
                    val index = tasks.indexOfFirst { it.id == task.id }
                    if (index >= 0) tasks[index] = updated
                    if (updated.done) cancelReminder(task) else if (updated.dueAt > System.currentTimeMillis()) schedule(updated)
                    saveTasks(); render()
                }
            })
            row.addView(Button(this).apply {
                text = "Edit"; isAllCaps = false
                setOnClickListener { editTask(task) }
            })
            row.addView(Button(this).apply {
                text = "×"; contentDescription = "Delete task"
                setOnClickListener {
                    AlertDialog.Builder(this@TaskPlannerActivity).setMessage("Delete this task?")
                        .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                            cancelReminder(task); tasks.removeAll { it.id == task.id }; saveTasks(); render()
                        }.show()
                }
            })
            card.addView(row)
            if (task.note.isNotBlank()) card.addView(TextView(this).apply {
                text = task.note; textSize = 12f; setTextColor(0xFFAFBDD4.toInt()); setPadding(0, dp(5), 0, 0)
            })
            card.addView(TextView(this).apply {
                text = (if (!task.done && task.dueAt < now) "OVERDUE • " else "REMINDER • ") +
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(task.dueAt)
                textSize = 11f; setTextColor(if (!task.done && task.dueAt < now) 0xFFFF9C9C.toInt() else 0xFF91A9D6.toInt())
                setPadding(0, dp(7), 0, 0)
            })
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }


    private fun editTask(task: Task) {
        val titleInput = EditText(this).apply { setText(task.title); hint = "Task title"; setTextColor(Color.WHITE) }
        val noteInput = EditText(this).apply { setText(task.note); hint = "Optional details"; setTextColor(Color.WHITE) }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(titleInput)
            addView(noteInput)
        }
        AlertDialog.Builder(this).setTitle("Edit task").setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save and reschedule") { _, _ ->
                val title = titleInput.text.toString().trim()
                if (title.isBlank()) { toast("Task title is required"); return@setPositiveButton }
                val oldDate = Calendar.getInstance().apply { timeInMillis = task.dueAt }
                DatePickerDialog(this, { _, year, month, day ->
                    TimePickerDialog(this, { _, hour, minute ->
                        val due = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        if (due <= System.currentTimeMillis() && !task.done) {
                            toast("Choose a future reminder time")
                            return@TimePickerDialog
                        }
                        cancelReminder(task)
                        val updated = task.copy(title = title, note = noteInput.text.toString().trim(), dueAt = due)
                        val index = tasks.indexOfFirst { it.id == task.id }
                        if (index >= 0) tasks[index] = updated
                        if (!updated.done) schedule(updated)
                        saveTasks()
                        render()
                        toast("Task updated")
                    }, oldDate.get(Calendar.HOUR_OF_DAY), oldDate.get(Calendar.MINUTE), false).show()
                }, oldDate.get(Calendar.YEAR), oldDate.get(Calendar.MONTH), oldDate.get(Calendar.DAY_OF_MONTH)).show()
            }.show()
    }


    private fun scheduleDailyBriefing() {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(
            this, DAILY_BRIEFING_REQUEST, Intent(this, DailyBriefingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
    }

    private fun cancelDailyBriefing() {
        val pending = PendingIntent.getBroadcast(
            this, DAILY_BRIEFING_REQUEST, Intent(this, DailyBriefingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
        pending.cancel()
    }

    private fun loadTasks() {
        tasks.clear()
        runCatching {
            val array = JSONArray(prefs.getString("tasks", "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                tasks.add(Task(o.getLong("id"), o.getString("title"), o.optString("note"), o.getLong("dueAt"), o.optBoolean("done")))
            }
        }
    }

    private fun saveTasks() {
        val array = JSONArray()
        tasks.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title).put("note", it.note).put("dueAt", it.dueAt).put("done", it.done)) }
        prefs.edit().putString("tasks", array.toString()).apply()
    }

    private fun panel() = android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF19243A.toInt(), 0xFF101A2B.toInt())
    ).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0xFF344668.toInt()) }

    private fun toast(message: String) = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val DAILY_BRIEFING_REQUEST = 81008 }
}
