package `in`.shraddha.ai.future

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import `in`.shraddha.ai.brain.PersistentChatMemory
import org.json.JSONArray
import org.json.JSONObject

/** Explicit, passphrase-protected backup of user-managed app data. Secrets and biometrics are excluded. */
class UserBackupManager(context: Context) {
    private val app = context.applicationContext

    fun export(passphrase: CharArray): String {
        val graph = PersistentKnowledgeGraph(app).load().getOrThrow()
        val graphJson = JSONObject()
            .put("nodes", JSONArray().apply {
                graph.allNodes().forEach { node ->
                    put(JSONObject().put("id", node.id).put("label", node.label).put("kind", node.kind)
                        .put("note", node.note).put("updatedAt", node.updatedAt))
                }
            })
            .put("edges", JSONArray().apply {
                graph.allEdges().forEach { edge ->
                    put(JSONObject().put("fromId", edge.fromId).put("relation", edge.relation).put("toId", edge.toId))
                }
            })
        val planner = app.getSharedPreferences("shraddha_task_planner", Context.MODE_PRIVATE)
        val tasks = planner.getString("tasks", "[]") ?: "[]"
        require(tasks.length <= 100_000) { "Task planner data is too large" }
        val twin = app.getSharedPreferences("shraddha_digital_twin", Context.MODE_PRIVATE)
        val memoryEnabled = PersistentChatMemory.isEnabled(app)
        val payload = JSONObject()
            .put("schema", "shraddha-backup-v2")
            .put("createdAt", System.currentTimeMillis())
            .put("assistantName", app.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE).getString("assistant_name", "Shraddha AI").orEmpty().take(60))
            .put("knowledgeGraph", graphJson)
            .put("activeProject", app.getSharedPreferences("shraddha_projects", Context.MODE_PRIVATE).getString("active_project", "").orEmpty())
            .put("digitalTwin", JSONObject().put("name", twin.getString("name", "").orEmpty().take(80))
                .put("language", twin.getString("language", "Hindi").orEmpty().take(40))
                .put("style", twin.getString("style", "step-by-step").orEmpty().take(80)))
            .put("tasks", tasks)
            .put("dailyBriefingEnabled", planner.getBoolean("daily_briefing_enabled", false))
            .put("conversationMemoryEnabled", memoryEnabled)
            .put("conversationRecap", if (memoryEnabled) PersistentChatMemory.recap(app).orEmpty() else "")
            .put("auditTrail", LocalAuditTrail(app).exportText().let { auditText -> auditText.substring(0, minOf(80_000, auditText.length)) })
        return SecureBackupCodec.encrypt(payload.toString(), passphrase)
    }

    fun restore(envelope: String, passphrase: CharArray): Result<Unit> = runCatching {
        val backup = JSONObject(SecureBackupCodec.decrypt(envelope, passphrase))
        require(backup.optString("schema") == "shraddha-backup-v2") { "Unsupported backup schema" }

        val graphJson = backup.getJSONObject("knowledgeGraph")
        val graph = KnowledgeGraph()
        val nodes = graphJson.getJSONArray("nodes")
        val edges = graphJson.getJSONArray("edges")
        require(nodes.length() <= KnowledgeGraph.MAX_NODES) { "Too many knowledge nodes" }
        require(edges.length() <= KnowledgeGraph.MAX_EDGES) { "Too many knowledge relationships" }
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            graph.upsert(KnowledgeNode(node.getString("id"), node.getString("label"), node.optString("kind", "Note"),
                node.optString("note"), node.optLong("updatedAt", System.currentTimeMillis())))
        }
        for (i in 0 until edges.length()) {
            val edge = edges.getJSONObject(i)
            graph.connect(edge.getString("fromId"), edge.getString("relation"), edge.getString("toId"))
        }

        val project = backup.optString("activeProject", "")
        require(project.length <= 50_000)
        if (project.isNotBlank()) JSONObject(project)
        val twin = backup.optJSONObject("digitalTwin") ?: JSONObject()
        val taskRaw = backup.optString("tasks", "[]")
        require(taskRaw.length <= 100_000)
        val sourceTasks = JSONArray(taskRaw)
        require(sourceTasks.length() <= 500) { "Backup contains too many tasks" }
        val cleanTasks = JSONArray()
        val ids = hashSetOf<Long>()
        for (i in 0 until sourceTasks.length()) {
            val task = sourceTasks.getJSONObject(i)
            val id = task.optLong("id", 0L)
            val title = task.optString("title").trim()
            val dueAt = task.optLong("dueAt", 0L)
            require(id > 0L && ids.add(id) && title.isNotBlank() && title.length <= 160 && dueAt > 0L) { "Invalid task record" }
            cleanTasks.put(JSONObject().put("id", id).put("title", title).put("note", task.optString("note").take(500))
                .put("dueAt", dueAt).put("done", task.optBoolean("done")))
        }
        val assistantName = backup.optString("assistantName", "Shraddha AI").trim().take(60).ifBlank { "Shraddha AI" }
        val recap = backup.optString("conversationRecap", "")
        require(recap.length <= 12_000) { "Conversation recap is too large" }
        val restoreMemory = backup.optBoolean("conversationMemoryEnabled") && recap.isNotBlank()

        // All data is parsed and validated before existing app state is changed.
        PersistentKnowledgeGraph(app).save(graph).getOrThrow()
        val planner = app.getSharedPreferences("shraddha_task_planner", Context.MODE_PRIVATE)
        val oldTasks = JSONArray(planner.getString("tasks", "[]") ?: "[]")
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0 until oldTasks.length()) {
            val id = oldTasks.optJSONObject(i)?.optLong("id") ?: continue
            val pending = PendingIntent.getBroadcast(app, id.hashCode(), Intent(app, ReminderReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.cancel(pending)
            pending.cancel()
        }
        check(planner.edit().putString("tasks", cleanTasks.toString())
            .putBoolean("daily_briefing_enabled", backup.optBoolean("dailyBriefingEnabled")).commit()) {
            "Could not restore task planner data"
        }
        val projectPrefs = app.getSharedPreferences("shraddha_projects", Context.MODE_PRIVATE)
        val projectEditor = projectPrefs.edit()
        if (project.isBlank()) projectEditor.remove("active_project") else projectEditor.putString("active_project", project)
        check(projectEditor.commit()) { "Could not restore project data" }
        check(app.getSharedPreferences("shraddha_digital_twin", Context.MODE_PRIVATE).edit()
            .putString("name", twin.optString("name").take(80))
            .putString("language", twin.optString("language", "Hindi").take(40))
            .putString("style", twin.optString("style", "step-by-step").take(80)).commit()) { "Could not restore digital twin settings" }
        check(app.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE).edit().putString("assistant_name", assistantName).commit()) {
            "Could not restore assistant name"
        }
        if (restoreMemory) {
            PersistentChatMemory.setEnabled(app, true)
            PersistentChatMemory.restore(app, recap).getOrThrow()
        } else {
            PersistentChatMemory.setEnabled(app, false)
            PersistentChatMemory.clear(app)
        }
        ReminderBootReceiver.restore(app)
        LocalAuditTrail(app).append("backup_restore", "completed", "encrypted user-selected backup")
    }
}
