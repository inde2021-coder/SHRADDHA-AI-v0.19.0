package `in`.shraddha.ai.future

/**
 * Shared orchestration boundary for Voice/Brain/Agents -> Planner -> App Operator workflows.
 *
 * This class owns plans, approvals, lifecycle and audit events. It never performs Android UI,
 * camera, microphone, network, Firebase or device actions itself; platform adapters must do so
 * only after receiving an approved RUNNING step and must report an independently observed result.
 */
class ShraddhaIntegrationHub(
    private val workflowEngine: WorkflowEngine = WorkflowEngine()
) {
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

    private var active: Session? = null

    @Synchronized
    fun createPlan(workflowId: String, title: String, actions: List<Action>, now: Long = System.currentTimeMillis()): Session {
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
        active = session
        return session
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
        active = session
        return session
    }
}
