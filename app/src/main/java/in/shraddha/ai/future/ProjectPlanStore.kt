package `in`.shraddha.ai.future

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONArray
import org.json.JSONObject

/** Encrypted local-first storage for Project Lab milestone plans. */
class ProjectPlanStore(context: Context) {
    private val store = EncryptedFeatureStore(context.applicationContext)

    @Synchronized
    fun loadAll(): Result<List<ProjectPlan>> = runCatching {
        val ids = readIndex()
        ids.map { id ->
            val raw = store.get(key(id)).getOrThrow()
            require(raw.isNotBlank()) { "Project index references missing encrypted data for $id; refusing to hide or overwrite the damaged project." }
            decode(raw)
        }.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun save(plan: ProjectPlan): Result<List<ProjectPlan>> = runCatching {
        validate(plan)
        val previousIds = readIndex()
        val ids = previousIds.filterNot { it == plan.id }.toMutableList()
        ids.add(0, plan.id)
        val retained = ids.take(MAX_PLANS)
        val values = linkedMapOf(
            key(plan.id) to encode(plan),
            INDEX_KEY to JSONArray(retained).toString()
        )
        val evictedKeys = (previousIds - retained.toSet()).map(::key).toSet()
        store.putAllAndRemove(values, evictedKeys).getOrThrow()
        retained.map { id ->
            val raw = store.get(key(id)).getOrThrow()
            require(raw.isNotBlank()) { "Saved project data is missing for $id; refusing to report a partial save." }
            decode(raw)
        }.sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun delete(id: String): Result<List<ProjectPlan>> = runCatching {
        require(ID_PATTERN.matches(id)) { "Invalid project ID." }
        val ids = readIndex().filterNot { it == id }
        // Delete the encrypted record and update its index in one durable transaction.
        store.putAllAndRemove(
            mapOf(INDEX_KEY to JSONArray(ids).toString()),
            setOf(key(id))
        ).getOrThrow()
        ids.map { item ->
            val raw = store.get(key(item)).getOrThrow()
            require(raw.isNotBlank()) { "Project index references missing encrypted data for $item." }
            decode(raw)
        }.sortedByDescending { it.createdAt }
    }

    /** Portable, versioned backup. Exported JSON contains project data; keep the file private. */
    @Synchronized
    fun exportJson(): Result<String> = runCatching {
        val plans = loadAll().getOrThrow()
        JSONObject()
            .put("format", "shraddha-project-backup")
            .put("version", BACKUP_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("plans", JSONArray().apply { plans.forEach { put(JSONObject(encode(it))) } })
            .toString(2)
    }

    /**
     * Validates the entire backup before writing anything. Existing IDs are rejected rather
     * than silently overwritten; the encrypted store is updated in one transaction.
     */
    @Synchronized
    fun importJson(raw: String): Result<List<ProjectPlan>> = runCatching {
        require(raw.isNotBlank() && raw.length <= MAX_BACKUP_CHARS) { "Backup is empty or exceeds the 1 MB limit." }
        val root = JSONObject(raw)
        require(root.getString("format") == "shraddha-project-backup") { "This is not a Shraddha Project Lab backup." }
        require(root.getInt("version") == BACKUP_VERSION) { "Unsupported project backup version." }
        val array = root.getJSONArray("plans")
        require(array.length() in 1..MAX_PLANS) { "Backup must contain 1-$MAX_PLANS projects." }
        val imported = (0 until array.length()).map { decode(array.getJSONObject(it).toString()) }
        require(imported.map { it.id }.distinct().size == imported.size) { "Backup contains duplicate project IDs." }
        val existing = loadAll().getOrThrow()
        val existingIds = existing.map { it.id }.toSet()
        require(imported.none { it.id in existingIds }) { "A project with the same ID already exists. Nothing was imported." }
        require(existing.size + imported.size <= MAX_PLANS) { "Import would exceed the $MAX_PLANS project limit." }
        val combined = (imported + existing).sortedByDescending { it.createdAt }
        val values = linkedMapOf<String, String>()
        combined.forEach { values[key(it.id)] = encode(it) }
        values[INDEX_KEY] = JSONArray(combined.map { it.id }).toString()
        store.putAllAndRemove(values, emptySet()).getOrThrow()
        combined
    }

    private fun readIndex(): List<String> {
        val raw = store.get(INDEX_KEY).getOrThrow()
        if (raw.isBlank()) return emptyList()
        val array = JSONArray(raw)
        require(array.length() <= MAX_PLANS) { "Project index exceeds the item limit." }
        val ids = (0 until array.length()).map { array.getString(it) }
        require(ids.all { ID_PATTERN.matches(it) }) { "Project index contains an invalid project ID; refusing to silently discard entries." }
        require(ids.distinct().size == ids.size) { "Project index contains duplicate IDs; refusing to merge ambiguous project records." }
        ids
    }

    private fun validate(plan: ProjectPlan) {
        require(ID_PATTERN.matches(plan.id)) { "Invalid project ID." }
        require(plan.title.isNotBlank() && plan.title.length <= 160) { "Project title is invalid." }
        require(plan.milestones.isNotEmpty() && plan.milestones.size <= MAX_MILESTONES) { "Project must contain 1-$MAX_MILESTONES milestones." }
        require(plan.createdAt > 0L) { "Project creation time is invalid." }
        val ids = plan.milestones.map { it.id }
        require(ids.distinct().size == ids.size && ids.all { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) }) {
            "Milestone IDs must be unique and valid."
        }
        val milestonesById = plan.milestones.associateBy { it.id }
        plan.milestones.forEach { milestone ->
            require(milestone.title.isNotBlank() && milestone.title.length <= 160)
            require(milestone.note.length <= 500)
            require(milestone.dependsOn.distinct().size == milestone.dependsOn.size) {
                "Milestone dependencies must not contain duplicates."
            }
            require(milestone.dependsOn.all { it in ids && it != milestone.id }) {
                "Milestone dependencies must reference another milestone in this project."
            }
            require(milestone.state != MilestoneState.DONE || milestone.dependsOn.all { dependencyId ->
                milestonesById[dependencyId]?.state == MilestoneState.DONE
            }) { "A completed milestone has an incomplete dependency." }
        }
        // A cyclic dependency graph can never reach a valid completed state.
        val byId = plan.milestones.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "Milestone dependencies contain a cycle." }
            byId.getValue(id).dependsOn.forEach(::visit)
            visiting.remove(id)
            visited.add(id)
        }
        ids.forEach(::visit)
    }

    private fun encode(plan: ProjectPlan): String = JSONObject()
        .put("schemaVersion", 1)
        .put("id", plan.id)
        .put("title", plan.title)
        .put("createdAt", plan.createdAt)
        .put("milestones", JSONArray().apply {
            plan.milestones.forEach { milestone ->
                put(JSONObject()
                    .put("id", milestone.id)
                    .put("title", milestone.title)
                    .put("state", milestone.state.name)
                    .put("dependsOn", JSONArray(milestone.dependsOn))
                    .put("note", milestone.note.take(500)))
            }
        }).toString()

    private fun decode(raw: String): ProjectPlan {
        val json = JSONObject(raw)
        require(json.optInt("schemaVersion") == 1) { "Unsupported project plan version." }
        val array = json.getJSONArray("milestones")
        require(array.length() in 1..MAX_MILESTONES)
        val milestones = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            // Unknown states indicate a newer/corrupt record; never silently reset progress to TODO.
            val state = MilestoneState.valueOf(item.getString("state"))
            val deps = item.optJSONArray("dependsOn") ?: JSONArray()
            ProjectMilestone(
                id = item.getString("id"),
                title = item.getString("title"),
                state = state,
                dependsOn = (0 until deps.length()).map { deps.getString(it) },
                note = item.optString("note").take(500)
            )
        }
        return ProjectPlan(
            id = json.getString("id"),
            title = json.getString("title"),
            milestones = milestones,
            createdAt = json.getLong("createdAt")
        ).also(::validate)
    }

    private fun key(id: String) = "project_plan_$id"

    companion object {
        private const val INDEX_KEY = "project_plan_index_v1"
        private const val MAX_PLANS = 20
        private const val MAX_MILESTONES = 30
        private const val BACKUP_VERSION = 1
        private const val MAX_BACKUP_CHARS = 1_000_000
        private val ID_PATTERN = Regex("p-[A-Za-z0-9_-]{1,48}")
    }
}
