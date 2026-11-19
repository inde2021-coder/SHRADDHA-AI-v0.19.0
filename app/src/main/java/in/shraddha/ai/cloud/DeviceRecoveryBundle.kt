package `in`.shraddha.ai.cloud

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import `in`.shraddha.ai.brain.SecureMemoryVault
import `in`.shraddha.ai.operator.learning.AppTrainingProfile
import `in`.shraddha.ai.operator.learning.AppProfileStore
import org.json.JSONArray
import org.json.JSONObject

/** Captures and restores explicitly selected, app-managed data; biometric templates are excluded. */
object DeviceRecoveryBundle {
    private val prefixes = setOf(
        "person_directory",
        "kids_profile",
        "kids_care_log",
        "task_inbox",
        "workflow_checkpoint_",
        "app_profile_"
    )
    private val legacyV1Keys = setOf(
        "person_directory", "kids_profile", "kids_care_log", "task_inbox",
        "workflow_checkpoint", "active_project", "digital_twin"
    )
    private const val MAX_BUNDLE_CHARS = 1_400_000

    data class Preview(
        val version: Int,
        val recordCount: Int,
        val appProfileCount: Int,
        val workflowCheckpointCount: Int,
        val hasMemory: Boolean,
        val hasActiveProject: Boolean,
        val hasKnowledgeGraph: Boolean
    )

    private data class Parsed(
        val version: Int,
        val records: Map<String, String>,
        val memory: String,
        val activeProject: String,
        val digitalTwin: JSONObject,
        val knowledgeGraph: String
    )

    fun capture(context: Context, maxCharacters: Int = 100_000): Result<String> = runCatching {
        require(maxCharacters in 100_000..MAX_BUNDLE_CHARS) { "Recovery bundle size limit is invalid" }
        val app = context.applicationContext
        val store = EncryptedFeatureStore(app)
        // Migrate any older plaintext app-training profiles before taking the encrypted snapshot.
        AppProfileStore(app).all()
        val records = store.getAllWithPrefixes(prefixes).getOrThrow()
        val memory = SecureMemoryVault(app).load().getOrThrow()
        val project = app.getSharedPreferences("shraddha_projects", Context.MODE_PRIVATE)
            .getString("active_project", "").orEmpty()
        val graph = app.getSharedPreferences("shraddha_knowledge_graph", Context.MODE_PRIVATE).getString("nodes", "[]").orEmpty()
        JSONArray(graph)
        val twinPrefs = app.getSharedPreferences("shraddha_digital_twin", Context.MODE_PRIVATE)
        val twin = JSONObject()
            .put("name", twinPrefs.getString("name", "").orEmpty())
            .put("language", twinPrefs.getString("language", "Hindi").orEmpty())
            .put("style", twinPrefs.getString("style", "step-by-step").orEmpty())
        val items = JSONObject()
        records.toSortedMap().forEach { (key, value) -> items.put(key, value) }
        val bundle = JSONObject()
            .put("version", 2)
            .put("schema", "shraddha-device-recovery-v2")
            .put("createdAt", System.currentTimeMillis())
            .put("items", items)
            .put("memory", memory)
            .put("preferences", JSONObject().put("activeProject", project).put("digitalTwin", twin).put("knowledgeGraph", graph))
            .toString()
        require(bundle.length <= maxCharacters) {
            "Selected recovery bundle exceeds the 100 KB Firestore limit; remove older records first."
        }
        bundle
    }

    fun preview(raw: String): Result<Preview> = runCatching {
        val parsed = parse(raw)
        Preview(
            version = parsed.version,
            recordCount = parsed.records.size,
            appProfileCount = parsed.records.keys.count { it.startsWith("app_profile_") },
            workflowCheckpointCount = parsed.records.keys.count { it.startsWith("workflow_checkpoint_") },
            hasMemory = parsed.memory.isNotBlank(),
            hasActiveProject = parsed.activeProject.isNotBlank(),
            hasKnowledgeGraph = parsed.knowledgeGraph != "[]"
        )
    }

    fun restore(context: Context, raw: String): Result<Preview> = runCatching {
        val app = context.applicationContext
        val parsed = parse(raw)
        val store = EncryptedFeatureStore(app)
        val memoryVault = SecureMemoryVault(app)

        // Capture the old local state first. If any target write fails, best-effort rollback
        // restores every collection and preference rather than leaving a silent partial restore.
        val oldRecords = store.getAllWithPrefixes(prefixes).getOrThrow()
        val oldMemory = memoryVault.load().getOrThrow()
        val projectPrefs = app.getSharedPreferences("shraddha_projects", Context.MODE_PRIVATE)
        val twinPrefs = app.getSharedPreferences("shraddha_digital_twin", Context.MODE_PRIVATE)
        val graphPrefs = app.getSharedPreferences("shraddha_knowledge_graph", Context.MODE_PRIVATE)
        val localMemoryPrefs = app.getSharedPreferences("shraddha_local_memory", Context.MODE_PRIVATE)
        val oldProject = projectPrefs.getString("active_project", "").orEmpty()
        val oldTwin = JSONObject()
            .put("name", twinPrefs.getString("name", "").orEmpty())
            .put("language", twinPrefs.getString("language", "Hindi").orEmpty())
            .put("style", twinPrefs.getString("style", "step-by-step").orEmpty())
        val oldMirrorMemory = localMemoryPrefs.getString("notes", "").orEmpty()
        val oldGraph = graphPrefs.getString("nodes", "[]").orEmpty()

        try {
            store.replaceAllWithPrefixes(parsed.records, prefixes).getOrThrow()
            check(writeProject(projectPrefs, parsed.activeProject)) { "Could not restore active project preferences" }
            check(writeTwin(twinPrefs, parsed.digitalTwin)) { "Could not restore digital twin preferences" }
            check(graphPrefs.edit().putString("nodes", parsed.knowledgeGraph).commit()) { "Could not restore knowledge graph" }
            if (parsed.memory.isBlank()) {
                check(memoryVault.clear()) { "Could not clear local encrypted memory" }
                check(localMemoryPrefs.edit().remove("notes").commit()) { "Could not clear local memory mirror" }
            } else {
                memoryVault.save(parsed.memory).getOrThrow()
                check(localMemoryPrefs.edit().putString("notes", parsed.memory).commit()) { "Could not update local memory mirror" }
            }
        } catch (error: Exception) {
            runCatching { store.replaceAllWithPrefixes(oldRecords, prefixes).getOrThrow() }
            runCatching { writeProject(projectPrefs, oldProject) }
            runCatching { writeTwin(twinPrefs, oldTwin) }
            runCatching { graphPrefs.edit().putString("nodes", oldGraph).commit() }
            runCatching {
                if (oldMemory.isBlank()) memoryVault.clear() else memoryVault.save(oldMemory).getOrThrow()
                if (oldMirrorMemory.isBlank()) localMemoryPrefs.edit().remove("notes").commit()
                else localMemoryPrefs.edit().putString("notes", oldMirrorMemory).commit()
            }
            throw error
        }
        Preview(
            version = parsed.version,
            recordCount = parsed.records.size,
            appProfileCount = parsed.records.keys.count { it.startsWith("app_profile_") },
            workflowCheckpointCount = parsed.records.keys.count { it.startsWith("workflow_checkpoint_") },
            hasMemory = parsed.memory.isNotBlank(),
            hasActiveProject = parsed.activeProject.isNotBlank(),
            hasKnowledgeGraph = parsed.knowledgeGraph != "[]"
        )
    }

    private fun parse(raw: String): Parsed {
        require(raw.isNotBlank() && raw.length <= MAX_BUNDLE_CHARS) { "Recovery bundle is empty or too large" }
        val root = JSONObject(raw)
        val version = root.optInt("version", -1)
        require(version == 1 || version == 2) { "Unsupported recovery bundle version" }
        val sourceItems = root.optJSONObject("items") ?: error("Recovery bundle is missing its records")
        require(sourceItems.length() <= 1000) { "Recovery bundle contains too many records" }
        val records = linkedMapOf<String, String>()
        val keys = sourceItems.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = sourceItems.get(key)
            require(value is String) { "Recovery record '$key' is not text" }
            val allowed = if (version == 1) key in legacyV1Keys else prefixes.any { key.startsWith(it) }
            require(allowed) { "Recovery bundle contains an unapproved key: $key" }
            if (version == 2) validateRecord(key, value)
            else if (key in setOf("person_directory", "kids_care_log", "task_inbox")) JSONArray(value)
            else if (key == "kids_profile" && value.isNotBlank()) JSONObject(value)
            if (version == 2) records[key] = value
            else if (key !in setOf("workflow_checkpoint", "active_project", "digital_twin")) records[key] = value
        }

        val memory = root.optString("memory", "")
        var project = ""
        var twin = JSONObject().put("name", "").put("language", "Hindi").put("style", "step-by-step")
        var graph = "[]"
        if (version == 2) {
            require(root.optString("schema") == "shraddha-device-recovery-v2") { "Recovery bundle schema mismatch" }
            val preferences = root.optJSONObject("preferences") ?: error("Recovery preferences are missing")
            project = preferences.optString("activeProject", "")
            twin = preferences.optJSONObject("digitalTwin") ?: error("Digital twin preferences are malformed")
            graph = preferences.optString("knowledgeGraph", "[]")
            JSONArray(graph)
            require(graph.length <= 80_000) { "Knowledge graph is too large" }
            require(project.length <= 100_000) { "Active project is too large" }
        } else {
            project = sourceItems.optString("active_project", "")
            val oldTwin = sourceItems.optString("digital_twin", "")
            if (oldTwin.isNotBlank()) twin = JSONObject(oldTwin)
        }
        require(memory.length <= 16_000) { "Recovered memory exceeds the supported limit" }
        require(twin.optString("name").length <= 100 && twin.optString("language").length <= 40 && twin.optString("style").length <= 80) {
            "Digital twin preferences are invalid"
        }
        return Parsed(version, records, memory, project, twin, graph)
    }

    private fun validateRecord(key: String, value: String) {
        when {
            key == "person_directory" -> {
                val array = JSONArray(value)
                require(array.length() <= 50)
                val ids = mutableSetOf<String>()
                for (i in 0 until array.length()) {
                    val person = array.getJSONObject(i)
                    val id = person.getString("id")
                    require(id.matches(Regex("[A-Za-z0-9_-]{8,64}")) && ids.add(id))
                }
            }
            key == "kids_profile" -> JSONObject(value)
            key == "kids_care_log" || key == "task_inbox" -> JSONArray(value)
            key.startsWith("workflow_checkpoint_") -> {
                require(key.matches(Regex("workflow_checkpoint_[a-f0-9]{32}")))
                val checkpoint = JSONObject(value)
                require(checkpoint.optString("packageName").isNotBlank())
                require(checkpoint.optString("workflowName").isNotBlank())
                require(checkpoint.optString("workflowHash").matches(Regex("[a-f0-9]{64}")))
                val total = checkpoint.optInt("totalSteps", -1)
                require(total in 1..100 && checkpoint.optInt("nextStep", -1) in 0..total)
                require(checkpoint.optString("state") in setOf("READY", "PAUSED", "WAITING_CONFIRMATION", "EXECUTING", "FAILED"))
            }
            key.startsWith("app_profile_") -> {
                require(key.matches(Regex("app_profile_[a-f0-9]{32}")))
                val profile = AppTrainingProfile.fromJson(JSONObject(value))
                require(profile.packageName.isNotBlank())
            }
            else -> error("Unsupported recovery key: $key")
        }
    }

    private fun writeProject(prefs: android.content.SharedPreferences, value: String): Boolean =
        prefs.edit().putString("active_project", value).commit()

    private fun writeTwin(prefs: android.content.SharedPreferences, twin: JSONObject): Boolean =
        prefs.edit()
            .putString("name", twin.optString("name", ""))
            .putString("language", twin.optString("language", "Hindi"))
            .putString("style", twin.optString("style", "step-by-step"))
            .commit()
}
