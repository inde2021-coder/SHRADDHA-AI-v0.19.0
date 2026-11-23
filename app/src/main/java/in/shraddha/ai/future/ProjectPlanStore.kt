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
        ids.mapNotNull { id ->
            store.get(key(id)).getOrThrow().takeIf { it.isNotBlank() }?.let(::decode)
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
        retained.mapNotNull { id ->
            store.get(key(id)).getOrThrow().takeIf { it.isNotBlank() }?.let(::decode)
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
        ids.mapNotNull { item ->
            store.get(key(item)).getOrThrow().takeIf { it.isNotBlank() }?.let(::decode)
        }.sortedByDescending { it.createdAt }
    }

    private fun readIndex(): List<String> {
        val raw = store.get(INDEX_KEY).getOrThrow()
        if (raw.isBlank()) return emptyList()
        val array = JSONArray(raw)
        require(array.length() <= MAX_PLANS) { "Project index exceeds the item limit." }
        return (0 until array.length()).map { array.getString(it) }
            .filter { ID_PATTERN.matches(it) }.distinct()
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
        plan.milestones.forEach { milestone ->
            require(milestone.title.isNotBlank() && milestone.title.length <= 160)
            require(milestone.note.length <= 500)
            require(milestone.dependsOn.all { it in ids && it != milestone.id })
        }
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
            val state = runCatching { MilestoneState.valueOf(item.optString("state")) }
                .getOrDefault(MilestoneState.TODO)
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
        private val ID_PATTERN = Regex("p-[A-Za-z0-9_-]{1,48}")
    }
}
