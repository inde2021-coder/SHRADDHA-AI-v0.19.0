package `in`.shraddha.ai.future

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONArray
import org.json.JSONObject

enum class ProjectWorkflowState { IDLE, RUNNING, PAUSED, FAILED, COMPLETED }
data class ProjectWorkflowSnapshot(
    val projectId: String,
    val state: ProjectWorkflowState,
    val currentMilestoneId: String?,
    val completedMilestoneIds: List<String>,
    val updatedAt: Long,
    val message: String,
    val revision: Long
)

/** Durable encrypted workflow journal. Recovery pauses interrupted work; it never replays side effects. */
class ProjectWorkflowStore(context: Context) {
    private val store = EncryptedFeatureStore(context.applicationContext)

    @Synchronized fun load(projectId: String): Result<ProjectWorkflowSnapshot?> = runCatching {
        validateId(projectId)
        val raw = store.get(key(projectId)).getOrThrow()
        if (raw.isBlank()) return@runCatching null
        decode(raw).let { snapshot ->
            if (snapshot.state == ProjectWorkflowState.RUNNING) {
                snapshot.copy(state = ProjectWorkflowState.PAUSED, updatedAt = System.currentTimeMillis(),
                    message = "Recovered after interruption. Review the last checkpoint before resuming; no action was replayed.",
                    revision = snapshot.revision + 1).also { save(it).getOrThrow() }
            } else snapshot
        }
    }

    @Synchronized fun start(plan: ProjectPlan): Result<ProjectWorkflowSnapshot> = runCatching {
        validatePlan(plan)
        val previous = load(plan.id).getOrThrow()
        require(previous == null || previous.state in setOf(ProjectWorkflowState.IDLE, ProjectWorkflowState.PAUSED, ProjectWorkflowState.FAILED, ProjectWorkflowState.COMPLETED)) {
            "This project workflow is already running."
        }
        val done = plan.milestones.filter { it.state == MilestoneState.DONE }.map { it.id }
        val next = plan.milestones.firstOrNull { it.id !in done && it.dependsOn.all(done::contains) }
        val state = if (next == null) ProjectWorkflowState.COMPLETED else ProjectWorkflowState.RUNNING
        val snapshot = ProjectWorkflowSnapshot(plan.id, state, next?.id, done, System.currentTimeMillis(),
            if (next == null) "All milestones are already complete." else "Workflow started. Next milestone is ready for execution.",
            (previous?.revision ?: 0L) + 1)
        save(snapshot).getOrThrow(); snapshot
    }

    @Synchronized fun checkpoint(plan: ProjectPlan, milestoneId: String, message: String): Result<ProjectWorkflowSnapshot> = runCatching {
        validatePlan(plan)
        val current = load(plan.id).getOrThrow() ?: error("Start the workflow before recording a checkpoint.")
        require(current.state == ProjectWorkflowState.RUNNING) { "Workflow is not running." }
        val milestone = plan.milestones.firstOrNull { it.id == milestoneId } ?: error("Milestone does not exist.")
        require(milestone.dependsOn.all(current.completedMilestoneIds::contains)) { "Milestone dependencies are not complete." }
        require(message.isNotBlank() && message.length <= 1000) { "Checkpoint message must contain 1-1000 characters." }
        val done = (current.completedMilestoneIds + milestoneId).distinct()
        val next = plan.milestones.firstOrNull { it.id !in done && it.dependsOn.all(done::contains) }
        val updated = current.copy(state = if (next == null) ProjectWorkflowState.COMPLETED else ProjectWorkflowState.RUNNING,
            currentMilestoneId = next?.id, completedMilestoneIds = done, updatedAt = System.currentTimeMillis(),
            message = message.trim(), revision = current.revision + 1)
        save(updated).getOrThrow(); updated
    }

    @Synchronized fun pause(projectId: String, reason: String = "Paused by user."): Result<ProjectWorkflowSnapshot> = transition(projectId, setOf(ProjectWorkflowState.RUNNING), ProjectWorkflowState.PAUSED, reason)
    @Synchronized fun resume(projectId: String): Result<ProjectWorkflowSnapshot> = transition(projectId, setOf(ProjectWorkflowState.PAUSED, ProjectWorkflowState.FAILED), ProjectWorkflowState.RUNNING, "Workflow resumed. Continue from the last durable checkpoint; do not repeat completed steps.")
    @Synchronized fun fail(projectId: String, reason: String): Result<ProjectWorkflowSnapshot> = transition(projectId, setOf(ProjectWorkflowState.RUNNING), ProjectWorkflowState.FAILED, reason)

    /** Roll back workflow progress to the last completed checkpoint, reopening later milestones in the plan. */
    @Synchronized fun rollbackLastCheckpoint(plan: ProjectPlan): Result<Pair<ProjectPlan, ProjectWorkflowSnapshot>> = runCatching {
        validatePlan(plan)
        val current = load(plan.id).getOrThrow() ?: error("No workflow history exists for this project.")
        require(current.state != ProjectWorkflowState.RUNNING) { "Pause the workflow before rollback." }
        require(current.completedMilestoneIds.isNotEmpty()) { "There is no completed checkpoint to roll back." }
        val removed = current.completedMilestoneIds.last()
        val resetIds = mutableSetOf(removed)
        var changed: Boolean
        do {
            changed = false
            plan.milestones.forEach { milestone ->
                if (milestone.dependsOn.any { it in resetIds } && resetIds.add(milestone.id)) changed = true
            }
        } while (changed)
        val remaining = current.completedMilestoneIds.filterNot { it in resetIds }
        val updatedPlan = plan.copy(milestones = plan.milestones.map { milestone ->
            if (milestone.id in resetIds) milestone.copy(state = MilestoneState.TODO, note = "") else milestone
        })
        val next = updatedPlan.milestones.firstOrNull { it.id !in remaining && it.dependsOn.all(remaining::contains) }
        val snapshot = current.copy(state = ProjectWorkflowState.PAUSED, currentMilestoneId = next?.id,
            completedMilestoneIds = remaining, updatedAt = System.currentTimeMillis(),
            message = "Rolled back checkpoint $removed. Review the project plan before resuming.", revision = current.revision + 1)
        save(snapshot).getOrThrow(); updatedPlan to snapshot
    }

    private fun transition(id: String, allowed: Set<ProjectWorkflowState>, target: ProjectWorkflowState, message: String): Result<ProjectWorkflowSnapshot> = runCatching {
        validateId(id)
        val current = load(id).getOrThrow() ?: error("No workflow exists for this project.")
        require(current.state in allowed) { "Cannot change workflow from ${current.state} to $target." }
        current.copy(state = target, updatedAt = System.currentTimeMillis(), message = message.take(1000), revision = current.revision + 1)
            .also { save(it).getOrThrow() }
    }

    private fun save(snapshot: ProjectWorkflowSnapshot): Result<Unit> =
        store.put(key(snapshot.projectId), JSONObject().put("schema", 1).put("projectId", snapshot.projectId)
            .put("state", snapshot.state.name).put("currentMilestoneId", snapshot.currentMilestoneId ?: JSONObject.NULL)
            .put("completed", JSONArray(snapshot.completedMilestoneIds)).put("updatedAt", snapshot.updatedAt)
            .put("message", snapshot.message.take(1000)).put("revision", snapshot.revision).toString())

    private fun decode(raw: String): ProjectWorkflowSnapshot {
        val json = JSONObject(raw)
        require(json.getInt("schema") == 1) { "Unsupported project workflow journal version." }
        val completed = json.getJSONArray("completed")
        val ids = (0 until completed.length()).map { completed.getString(it) }
        require(ids.distinct().size == ids.size && ids.all { it.matches(MILESTONE_ID) }) { "Workflow checkpoint list is invalid." }
        return ProjectWorkflowSnapshot(json.getString("projectId"), ProjectWorkflowState.valueOf(json.getString("state")),
            json.optString("currentMilestoneId").takeIf { it.isNotBlank() && it != "null" }, ids,
            json.getLong("updatedAt"), json.optString("message").take(1000), json.getLong("revision"))
            .also { validateId(it.projectId); require(it.updatedAt > 0 && it.revision >= 1) }
    }

    private fun validatePlan(plan: ProjectPlan) {
        validateId(plan.id)
        require(plan.milestones.isNotEmpty() && plan.milestones.size <= 30)
        require(plan.milestones.map { it.id }.distinct().size == plan.milestones.size)
        require(plan.milestones.all { it.id.matches(MILESTONE_ID) && it.dependsOn.all { dep -> plan.milestones.any { it.id == dep } } })
    }
    private fun validateId(id: String) { require(id.matches(Regex("p-[A-Za-z0-9_-]{1,48}"))) { "Invalid project ID." } }
    private fun key(id: String) = "project_workflow_$id"
    private companion object { val MILESTONE_ID = Regex("[A-Za-z0-9_-]{1,40}") }
}