package `in`.shraddha.ai.future

import `in`.shraddha.ai.operator.PlannedOperatorAction
import `in`.shraddha.ai.operator.TaskPlannerOperatorBridge
import `in`.shraddha.ai.operator.OperatorStep
import `in`.shraddha.ai.operator.security.OperatorSafetyPolicy
import `in`.shraddha.ai.future.ShraddhaIntegrationHub

/**
 * One orchestration boundary for text, voice and future desktop clients.
 * LLM prose is never converted implicitly into device actions: device work must be supplied
 * as typed Operator steps and is executed only through the verified Operator bridge.
 *
 * This queue is process-local. WorkManager remains the durable background queue; this class
 * must not be described as crash-resumable until a persistent scheduler store is integrated.
 */
class MainBrain(
    maxParallelWorkflows: Int = 1,
    private val executeVerified: (OperatorStep, confirmed: Boolean) -> Boolean,
    private val audit: (event: String, outcome: String, detail: String) -> Unit = { _, _, _ -> }
) {
    data class WorkflowRequest(
        val id: String,
        val title: String,
        val actions: List<PlannedOperatorAction>,
        val priority: Int = 3,
        val dependsOn: Set<String> = emptySet()
    )

    private val scheduler = SharedTaskScheduler(maxParallelWorkflows)
    private val requests = linkedMapOf<String, WorkflowRequest>()
    private val bridges = linkedMapOf<String, TaskPlannerOperatorBridge>()
    private var activeWorkflowId: String? = null

    /** Voice and text adapters use this same typed command boundary. */
    @Synchronized
    fun enqueueOperatorWorkflow(request: WorkflowRequest): SharedTaskScheduler.Snapshot {
        require(request.actions.isNotEmpty() && request.actions.size <= 100) {
            "An Operator workflow must contain 1-100 typed steps"
        }
        require(request.actions.map { it.id }.distinct().size == request.actions.size) {
            "Operator step IDs must be unique"
        }
        request.actions.forEach { action ->
            val decision = OperatorSafetyPolicy.assess(action.step.action.name, action.step.target, action.step.value)
            require(decision.risk != OperatorSafetyPolicy.Risk.BLOCK) { decision.reason }
        }
        val queued = scheduler.submitAll(listOf(
            SharedTaskScheduler.Task(
                id = request.id,
                title = request.title,
                dependsOn = request.dependsOn,
                priority = request.priority
            )
        )).single()
        requests[request.id] = request
        audit("main_brain_queue", "queued", "workflow=${request.id}; steps=${request.actions.size}")
        return queued
    }

    /** Claims one dependency-ready workflow and runs it until completion or an approval gate. */
    @Synchronized
    fun startNextWorkflow(): MainBrainExecutionResult {
        check(activeWorkflowId == null) { "A workflow is already active; approve or finish it first" }
        val claimed = scheduler.claimReady().firstOrNull()
            ?: return MainBrainExecutionResult.NoReadyWorkflow(scheduler.snapshot())
        val request = requests[claimed.task.id]
        if (request == null) {
            scheduler.fail(claimed.task.id, "Queued workflow request is missing")
            return MainBrainExecutionResult.Failed(claimed.task.id, "Queued workflow request is missing", scheduler.snapshot())
        }
        val bridge = TaskPlannerOperatorBridge(executeVerified = executeVerified, audit = audit)
        bridges[request.id] = bridge
        activeWorkflowId = request.id
        return runCatching {
            bridge.createPlan(request.id, request.title, request.actions)
            val session = bridge.start()
            settleIfTerminal(request.id, session.workflow.status)
            MainBrainExecutionResult.Started(request.id, session, scheduler.snapshot())
        }.getOrElse { error ->
            scheduler.fail(request.id, (error.message ?: "Workflow could not start").take(500))
            bridges.remove(request.id)
            activeWorkflowId = null
            audit("main_brain_queue", "failed", "workflow=${request.id}; reason=${error.message.orEmpty().take(160)}")
            MainBrainExecutionResult.Failed(request.id, error.message ?: "Workflow could not start", scheduler.snapshot())
        }
    }

    /** Approval is step-scoped; declining stops this queued workflow without dispatching the step. */
    @Synchronized
    fun approveCurrentStep(accepted: Boolean): MainBrainExecutionResult {
        val id = activeWorkflowId ?: error("No active Main Brain workflow")
        val bridge = bridges[id] ?: error("Active workflow bridge is unavailable")
        return runCatching {
            val session = bridge.approveCurrentStep(accepted)
            if (!accepted) {
                scheduler.cancel(id)
                bridges.remove(id)
                activeWorkflowId = null
                audit("main_brain_queue", "cancelled_by_user", "workflow=$id")
            } else {
                settleIfTerminal(id, session.workflow.status)
            }
            MainBrainExecutionResult.ApprovalUpdated(id, session, scheduler.snapshot())
        }.getOrElse { error ->
            runCatching { scheduler.fail(id, (error.message ?: "Approval flow failed").take(500)) }
            bridges.remove(id)
            activeWorkflowId = null
            MainBrainExecutionResult.Failed(id, error.message ?: "Approval flow failed", scheduler.snapshot())
        }
    }

    @Synchronized
    fun queueSnapshot(): List<SharedTaskScheduler.Snapshot> = scheduler.snapshot()

    @Synchronized
    fun activeWorkflowId(): String? = activeWorkflowId

    /** Council is reasoning only; it never silently turns generated prose into device actions. */
    fun reasonWithCouncil(
        goal: String,
        language: String = "AUTO",
        provider: String,
        executor: (CouncilRole, String, String) -> String,
        shouldContinue: () -> Boolean = { true },
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }
    ): Result<CouncilExecutionReport> =
        AgentCouncil.execute(goal, language, provider, executor, onProgress, shouldContinue)

    private fun settleIfTerminal(id: String, status: WorkflowStatus) {
        when (status) {
            WorkflowStatus.COMPLETED -> {
                scheduler.complete(id)
                bridges.remove(id)
                activeWorkflowId = null
                audit("main_brain_queue", "completed", "workflow=$id")
            }
            WorkflowStatus.FAILED, WorkflowStatus.CANCELLED -> {
                scheduler.fail(id, "Operator workflow ended with $status")
                bridges.remove(id)
                activeWorkflowId = null
                audit("main_brain_queue", "failed", "workflow=$id; status=$status")
            }
            else -> Unit
        }
    }
}

sealed class MainBrainExecutionResult {
    data class Started(
        val workflowId: String,
        val session: ShraddhaIntegrationHub.Session,
        val queue: List<SharedTaskScheduler.Snapshot>
    ) : MainBrainExecutionResult()

    data class ApprovalUpdated(
        val workflowId: String,
        val session: ShraddhaIntegrationHub.Session,
        val queue: List<SharedTaskScheduler.Snapshot>
    ) : MainBrainExecutionResult()

    data class NoReadyWorkflow(val queue: List<SharedTaskScheduler.Snapshot>) : MainBrainExecutionResult()
    data class Failed(val workflowId: String, val reason: String, val queue: List<SharedTaskScheduler.Snapshot>) : MainBrainExecutionResult()
}
