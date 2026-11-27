package `in`.shraddha.ai.operator

import `in`.shraddha.ai.future.Risk
import `in`.shraddha.ai.future.SelfReviewEngine
import `in`.shraddha.ai.future.ShraddhaIntegrationHub
import `in`.shraddha.ai.future.WorkflowStatus
import `in`.shraddha.ai.operator.security.OperatorSafetyPolicy

/**
 * Connects dependency-ordered plans to the real accessibility Operator.
 * The supplied executor must observe, dispatch and independently verify one step.
 * This bridge never executes a step before the hub reports RUNNING.
 */
data class PlannedOperatorAction(
    val id: String,
    val title: String,
    val step: OperatorStep,
    val dependsOn: Set<String> = emptySet(),
    val priority: Int = 3,
    val estimatedMinutes: Int = 1,
    val risk: Risk = Risk.READ_ONLY,
    val requiresApproval: Boolean = false
)

class TaskPlannerOperatorBridge(
    private val hub: ShraddhaIntegrationHub = ShraddhaIntegrationHub(),
    private val executeVerified: (OperatorStep, confirmed: Boolean) -> Boolean,
    private val audit: (event: String, outcome: String, detail: String) -> Unit = { _, _, _ -> },
    private val beforeExecute: (workflowId: String, action: PlannedOperatorAction) -> Boolean = { _, _ -> true },
    private val afterExecute: (workflowId: String, action: PlannedOperatorAction, verified: Boolean) -> Boolean = { _, _, _ -> true }
) {
    private val actions = linkedMapOf<String, PlannedOperatorAction>()
    private val verified = mutableSetOf<String>()

    @Synchronized
    fun createPlan(workflowId: String, title: String, planned: List<PlannedOperatorAction>): ShraddhaIntegrationHub.Session {
        require(planned.isNotEmpty() && planned.size <= 100)
        require(planned.map { it.id }.distinct().size == planned.size)
        planned.forEach { action ->
            require(action.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid action ID: ${action.id}" }
            require(action.title.isNotBlank() && action.title.length <= 120) { "Every action needs a short, non-empty title." }
        }
        validateDependencyGraph(planned)
        val normalized = planned.map { action ->
            require(action.id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            require(action.title.isNotBlank() && action.title.length <= 120)
            val safety = OperatorSafetyPolicy.assess(action.step.action.name, action.step.target, action.step.value, action.step.expectedVisibleText)
            require(safety.risk != OperatorSafetyPolicy.Risk.BLOCK) {
                "Operator plan contains an action blocked by safety policy: ${safety.reason}"
            }
            val needsApproval = action.requiresApproval ||
                action.risk == Risk.SENSITIVE || action.risk == Risk.IRREVERSIBLE ||
                safety.risk == OperatorSafetyPolicy.Risk.CONFIRM
            action.copy(
                risk = if (needsApproval) Risk.SENSITIVE else action.risk,
                requiresApproval = needsApproval
            )
        }
        val session = hub.createPlan(workflowId, title, normalized.map {
            ShraddhaIntegrationHub.Action(it.id, it.title, it.dependsOn, it.priority,
                it.estimatedMinutes, it.risk, it.requiresApproval)
        })
        // Publish adapters only after the hub accepts the complete replacement plan.
        // Rejected plans must leave the active workflow and its adapters untouched.
        actions.clear()
        normalized.forEach { actions[it.id] = it }
        verified.clear()
        audit("task_plan", "created", "workflow=${workflowId}; steps=${planned.size}")
        return session
    }

    /**
     * Validate the complete dependency graph before creating a session or dispatching work.
     * Missing references and cycles are rejected rather than silently treated as ready steps.
     */
    private fun validateDependencyGraph(planned: List<PlannedOperatorAction>) {
        val ids = planned.map { it.id }.toSet()
        planned.forEach { action ->
            require(action.id !in action.dependsOn) {
                "Action ${action.id} cannot depend on itself."
            }
            val missing = action.dependsOn - ids
            require(missing.isEmpty()) {
                "Action ${action.id} references missing dependencies: ${missing.joinToString()}"
            }
        }
        val remaining = planned.associate { it.id to it.dependsOn.size }.toMutableMap()
        val dependents = mutableMapOf<String, MutableList<String>>()
        planned.forEach { action ->
            action.dependsOn.forEach { dependency ->
                dependents.getOrPut(dependency) { mutableListOf() }.add(action.id)
            }
        }
        val ready = remaining.filterValues { it == 0 }.keys.toMutableList()
        var visited = 0
        while (ready.isNotEmpty()) {
            val completed = ready.removeAt(0)
            visited++
            dependents[completed].orEmpty().forEach { dependent ->
                val next = (remaining[dependent] ?: 0) - 1
                remaining[dependent] = next
                if (next == 0) ready.add(dependent)
            }
        }
        require(visited == planned.size) {
            "Workflow dependencies contain a cycle. No action was started."
        }
    }

    /** Starts the plan and automatically runs only steps that do not require user approval. */
    @Synchronized
    fun start(): ShraddhaIntegrationHub.Session {
        val started = hub.start()
        audit("task_plan", "started", "workflow=${started.workflow.id}")
        return dispatchReadySteps(started)
    }

    /** Approval applies only to the currently displayed step; declining never dispatches it. */
    @Synchronized
    fun approveCurrentStep(accepted: Boolean): ShraddhaIntegrationHub.Session {
        val updated = hub.approveCurrentStep(accepted)
        audit("task_approval", if (accepted) "approved" else "declined",
            "workflow=${updated.workflow.id}; step=${updated.workflow.steps.getOrNull(updated.workflow.currentStep)?.id.orEmpty()}")
        return if (accepted) dispatchReadySteps(updated) else updated
    }

    @Synchronized
    fun current(): ShraddhaIntegrationHub.Session? = hub.current()

    private fun dispatchReadySteps(initial: ShraddhaIntegrationHub.Session): ShraddhaIntegrationHub.Session {
        var session = initial
        while (session.workflow.status == WorkflowStatus.RUNNING) {
            val currentStep = session.workflow.steps.getOrNull(session.workflow.currentStep)
                ?: return hub.failCurrentStep("Planner index is outside the workflow")
            val action = actions[currentStep.id]
                ?: return hub.failCurrentStep("No approved Operator adapter exists for ${currentStep.id}")
            val approvalGranted = session.audit.any {
                it.actionId == action.id && it.event == "STEP_APPROVED"
            }
            val needsApproval = action.requiresApproval ||
                action.risk == Risk.SENSITIVE || action.risk == Risk.IRREVERSIBLE
            if (needsApproval && !approvalGranted) {
                return hub.failCurrentStep("Sensitive action reached execution without step-specific approval")
            }
            audit("operator_dispatch", "requested", "workflow=${session.workflow.id}; step=${action.id}")
            val journalReady = runCatching { beforeExecute(session.workflow.id, action) }.getOrElse { false }
            if (!journalReady) {
                audit("operator_dispatch", "journal_failed", "workflow=${session.workflow.id}; step=${action.id}")
                return hub.failCurrentStep("Secure action journal could not be committed; action was not executed")
            }
            val executed = runCatching { executeVerified(action.step, approvalGranted) }.getOrElse { false }
            val journalCommitted = runCatching { afterExecute(session.workflow.id, action, executed) }.getOrElse { false }
            if (!executed || !journalCommitted) {
                audit("operator_dispatch", "failed", "workflow=${session.workflow.id}; step=${action.id}")
                return hub.failCurrentStep(if (!executed) "Operator action failed or its screen result could not be verified" else "Action was verified but durable journal update failed; workflow stopped")
            }
            verified.add(action.id)
            session = hub.markActionDispatched()
            session = hub.verifyCurrentStep(true)
            audit("operator_dispatch", "verified", "workflow=${session.workflow.id}; step=${action.id}")
            if (session.workflow.status == WorkflowStatus.COMPLETED) {
                session = hub.reviewCompletion(actions.values.map { item ->
                    SelfReviewEngine.Check("step_${item.id}", "Operator independently verified ${item.title}", item.id in verified)
                })
                audit("task_plan", if (session.workflow.status == WorkflowStatus.COMPLETED) "completed" else "review_failed",
                    "workflow=${session.workflow.id}; verified=${verified.size}")
            }
        }
        return session
    }
}
