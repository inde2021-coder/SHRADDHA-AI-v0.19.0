package `in`.shraddha.ai.future

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared orchestration boundary for Voice/Brain/Agents -> Planner -> App Operator workflows.
 *
 * This class owns plans, approvals, lifecycle and audit events. It never performs Android UI,
 * camera, microphone, network, Firebase or device actions itself; platform adapters must do so
 * only after receiving an approved RUNNING step and must report an independently observed result.
 */
class ShraddhaIntegrationHub(
    private val workflowEngine: WorkflowEngine = WorkflowEngine(),
    context: Context? = null
) {
    private val persistence = context?.applicationContext?.let(::EncryptedFeatureStore)
    private val persistenceKey = "integration_hub_active_v1"
    private var active: Session? = persistence?.let { loadPersisted(it) }

    data class Action(
        val id: String,
        val title: String,
        val dependsOn: Set<String> = emptySet(),
        val priority: Int = 3,
        val estimatedMinutes: Int = 15,
        val risk: Risk = Risk.READ_ONLY,
        val requiresApproval: Boolean = false
    )

    data class AuditEvent(
        val workflowId: String,
        val actionId: String?,
        val event: String,
        val detail: String,
        val timestamp: Long
    )

    data class Session(
        val plan: AutonomousTaskPlanner.Plan,
        val workflow: WorkflowSnapshot,
        val audit: List<AuditEvent>
    )


    @Synchronized
    fun createPlan(workflowId: String, title: String, actions: List<Action>, now: Long = System.currentTimeMillis()): Session {
        require(actions.isNotEmpty() && actions.size <= 256) { "A workflow must contain 1-256 actions." }
        require(workflowId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(title.isNotBlank() && title.length <= 120)
        val planned = AutonomousTaskPlanner.plan(actions.map {
            AutonomousTaskPlanner.Task(it.id, it.title, it.dependsOn, it.priority, it.estimatedMinutes)
        })
        val byId = actions.associateBy { it.id }
        val steps = planned.ordered.map { task ->
            val action = byId.getValue(task.id)
            WorkflowStep(action.id, action.title, action.risk, action.requiresApproval)
        }
        val snapshot = WorkflowSnapshot(workflowId, title.trim(), WorkflowStatus.DRAFT, 0, steps, "Plan ready; no action has run")
        val session = Session(planned, snapshot, listOf(AuditEvent(workflowId, null, "PLAN_CREATED", "${steps.size} ordered action(s)", now)))
        return store(session)
    }

    @Synchronized
    fun start(now: Long = System.currentTimeMillis()): Session = update(now, "WORKFLOW_STARTED") {
        workflowEngine.start(it)
    }

    /** Approval is scoped to the current step only. Declining pauses without executing anything. */
    @Synchronized
    fun approveCurrentStep(accepted: Boolean, now: Long = System.currentTimeMillis()): Session {
        val current = requireSession()
        require(current.workflow.status == WorkflowStatus.WAITING_FOR_APPROVAL) { "No action is waiting for approval" }
        return update(now, if (accepted) "STEP_APPROVED" else "STEP_DECLINED") {
            workflowEngine.approve(it, accepted)
        }
    }

    /** Called by a platform adapter only after it has actually dispatched the approved action. */
    @Synchronized
    fun markActionDispatched(now: Long = System.currentTimeMillis()): Session {
        val current = requireSession()
        require(current.workflow.status == WorkflowStatus.RUNNING) { "Action is not approved and ready to run" }
        return update(now, "ACTION_DISPATCHED") { workflowEngine.markExecuted(it) }
    }

    /** A step advances only after the adapter independently observes the expected result. */
    @Synchronized
    fun verifyCurrentStep(success: Boolean, now: Long = System.currentTimeMillis()): Session {
        val current = requireSession()
        require(current.workflow.status == WorkflowStatus.VERIFYING) { "No dispatched action is awaiting verification" }
        return update(now, if (success) "ACTION_VERIFIED" else "VERIFICATION_FAILED") {
            workflowEngine.markVerified(it, success)
        }
    }

    /** Fail closed when the platform adapter cannot execute a planned action. */
    @Synchronized
    fun failCurrentStep(reason: String, now: Long = System.currentTimeMillis()): Session {
        val current = requireSession()
        require(current.workflow.status == WorkflowStatus.RUNNING || current.workflow.status == WorkflowStatus.VERIFYING) {
            "No active action can be failed"
        }
        return update(now, "ACTION_FAILED") { workflowEngine.markFailed(it, reason) }
    }

    /** Final self-review can downgrade a nominally completed workflow; it never auto-fixes code/actions. */
    @Synchronized
    fun reviewCompletion(checks: List<SelfReviewEngine.Check>, now: Long = System.currentTimeMillis()): Session {
        val current = requireSession()
        require(current.workflow.status == WorkflowStatus.COMPLETED) { "Workflow must finish all step verifications before final review" }
        val review = SelfReviewEngine.review(checks)
        val next = if (review.passed) current.workflow.copy(message = "All steps verified; final review passed (${review.scorePercent}%)")
            else current.workflow.copy(status = WorkflowStatus.FAILED, message = "Final review failed (${review.scorePercent}%): ${review.summary}")
        return store(current.copy(workflow = next, audit = current.audit + AuditEvent(
            current.workflow.id, null, if (review.passed) "FINAL_REVIEW_PASSED" else "FINAL_REVIEW_FAILED",
            review.summary, now
        )))
    }

    @Synchronized
    fun current(): Session? = active

    private fun update(now: Long, event: String, transform: (WorkflowSnapshot) -> WorkflowSnapshot): Session {
        val current = requireSession()
        val step = current.workflow.steps.getOrNull(current.workflow.currentStep)
        val next = transform(current.workflow)
        return store(current.copy(workflow = next, audit = current.audit + AuditEvent(
            current.workflow.id, step?.id, event, next.message, now
        )))
    }

    private fun requireSession(): Session = active ?: error("Create a task plan before starting a workflow")

    private fun store(session: Session): Session {
        persistence?.let { it.put(persistenceKey, encode(session)).getOrThrow() }
        active = session
        return session
    }

    private fun encode(session: Session): String {
        val planTasks = JSONArray().apply {
            session.plan.ordered.forEach { task ->
                put(JSONObject().put("id", task.id).put("title", task.title)
                    .put("dependsOn", JSONArray(task.dependsOn.toList()))
                    .put("priority", task.priority).put("estimatedMinutes", task.estimatedMinutes))
            }
        }
        val steps = JSONArray().apply {
            session.workflow.steps.forEach { step ->
                put(JSONObject().put("id", step.id).put("title", step.title)
                    .put("risk", step.risk.name).put("requiresApproval", step.requiresApproval))
            }
        }
        val audit = JSONArray().apply {
            session.audit.forEach { event ->
                put(JSONObject().put("workflowId", event.workflowId).put("actionId", event.actionId)
                    .put("event", event.event).put("detail", event.detail).put("timestamp", event.timestamp))
            }
        }
        return JSONObject().put("schemaVersion", 1)
            .put("plan", JSONObject().put("goal", session.plan.ordered.joinToString("\\n") { it.title }.take(4_000))
                .put("totalEstimatedMinutes", session.plan.totalEstimatedMinutes).put("tasks", planTasks))
            .put("workflow", JSONObject().put("id", session.workflow.id).put("title", session.workflow.title)
                .put("status", session.workflow.status.name).put("currentStep", session.workflow.currentStep)
                .put("message", session.workflow.message).put("steps", steps))
            .put("audit", audit).toString()
    }

    private fun loadPersisted(store: EncryptedFeatureStore): Session? {
        val raw = store.get(persistenceKey).getOrThrow()
        if (raw.isBlank()) return null
        val root = JSONObject(raw)
        require(root.getInt("schemaVersion") == 1) { "Unsupported workflow recovery record version." }
        val planJson = root.getJSONObject("plan")
        val taskArray = planJson.getJSONArray("tasks")
        require(taskArray.length() in 1..256) { "Persisted workflow plan is malformed." }
        val tasks = (0 until taskArray.length()).map { index ->
            val item = taskArray.getJSONObject(index)
            val deps = item.getJSONArray("dependsOn")
            val dependencies = (0 until deps.length()).map { deps.getString(it) }.toSet()
            AutonomousTaskPlanner.Task(
                item.getString("id"), item.getString("title"), dependencies,
                item.getInt("priority"), item.getInt("estimatedMinutes")
            )
        }
        val plan = AutonomousTaskPlanner.plan(tasks)
        val workflowJson = root.getJSONObject("workflow")
        val stepArray = workflowJson.getJSONArray("steps")
        require(stepArray.length() in 1..256) { "Persisted workflow steps are malformed." }
        val steps = (0 until stepArray.length()).map { index ->
            val item = stepArray.getJSONObject(index)
            WorkflowStep(item.getString("id"), item.getString("title"),
                Risk.valueOf(item.getString("risk")), item.getBoolean("requiresApproval"))
        }
        var status = WorkflowStatus.valueOf(workflowJson.getString("status"))
        var message = workflowJson.getString("message")
        val auditArray = root.getJSONArray("audit")
        require(auditArray.length() in 1..2_000) { "Persisted workflow audit is malformed." }
        val audit = (0 until auditArray.length()).map { index ->
            val item = auditArray.getJSONObject(index)
            AuditEvent(item.getString("workflowId"), item.optString("actionId").takeIf { it.isNotBlank() },
                item.getString("event"), item.getString("detail"), item.getLong("timestamp"))
        }.toMutableList()
        val workflowId = workflowJson.getString("id")
        if (status == WorkflowStatus.RUNNING || status == WorkflowStatus.VERIFYING) {
            status = WorkflowStatus.FAILED
            message = "Process restarted during an action. No action was replayed; inspect the target app and create a new approved run."
            audit += AuditEvent(workflowId, steps.getOrNull(workflowJson.getInt("currentStep"))?.id,
                "PROCESS_RESTARTED_REQUIRES_REVIEW", message, System.currentTimeMillis())
        }
        val workflow = WorkflowSnapshot(
            workflowId, workflowJson.getString("title"), status,
            workflowJson.getInt("currentStep"), steps, message
        )
        require(workflow.currentStep in workflow.steps.indices)
        return Session(plan, workflow, audit.toList())
    }
}
