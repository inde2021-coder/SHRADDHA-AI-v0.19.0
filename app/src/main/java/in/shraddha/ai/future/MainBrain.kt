package `in`.shraddha.ai.future

import `in`.shraddha.ai.operator.PlannedOperatorAction
import `in`.shraddha.ai.operator.TaskPlannerOperatorBridge
import `in`.shraddha.ai.operator.OperatorStep
import `in`.shraddha.ai.operator.security.OperatorSafetyPolicy
import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import kotlinx.coroutines.CancellationException
import `in`.shraddha.ai.future.ShraddhaIntegrationHub

/**
 * One orchestration boundary for text, voice and future desktop clients.
 * LLM prose is never converted implicitly into device actions: device work must be supplied
 * as typed Operator steps and is executed only through the verified Operator bridge.
 *
 * Pending typed requests are stored with Android Keystore AES-GCM encryption and restored after
 * process restart. A workflow already marked RUNNING is failed closed and never blindly replayed.
 */
class MainBrain(
    maxParallelWorkflows: Int = 1,
    private val executeVerified: (OperatorStep, confirmed: Boolean) -> Boolean,
    private val audit: (event: String, outcome: String, detail: String) -> Unit = { _, _, _ -> },
    context: Context? = null
) {
    data class WorkflowRequest(
        val id: String,
        val title: String,
        val actions: List<PlannedOperatorAction>,
        val priority: Int = 3,
        val dependsOn: Set<String> = emptySet()
    )

    companion object {
        /** Shared voice/text planning entry point; the resulting plan is still non-executable. */
        fun createCouncilPlan(
            goal: String,
            workflowId: String,
            title: String = goal.trim().take(120)
        ): Pair<CouncilPlan, ShraddhaIntegrationHub.Session> {
            val council = AgentCouncil.plan(goal)
            val actions = council.roles.mapIndexed { index, role ->
                ShraddhaIntegrationHub.Action(
                    id = role.name.lowercase().replace(Regex("[^a-z0-9_-]"), "_"),
                    title = role.responsibility,
                    dependsOn = if (index == 0) emptySet() else setOf(
                        council.roles[index - 1].name.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
                    ),
                    priority = if (role.name == "Verifier" || role.name == "Critic") 1 else 2,
                    estimatedMinutes = 10
                )
            }
            val session = ShraddhaIntegrationHub().createPlan(
                workflowId = workflowId,
                title = title.ifBlank { "Shraddha AI Council plan" },
                actions = actions
            )
            return council to session
        }

        /**
         * Voice device/app tools are serialized through the same durable scheduler as Operator
         * and WorkManager. A process death while RUNNING marks the call failed; it is never replayed.
         */
        suspend fun executeVoiceTool(context: Context, toolName: String, args: JSONObject): JSONObject {
            // Website Forge creates its own long-running Main Brain scheduler task after
            // validating/opening the Forge UI. Do not occupy a parent slot and then deadlock
            // while the Forge task claims the same single-worker scheduler.
            if (toolName == "build_website") {
                return com.Lia.assistant.action.ActionExecutor.execute(context.applicationContext, toolName, args)
            }
            val scheduler = MainBrainSchedulerRuntime.get(context.applicationContext)
            val taskId = "voice_" + UUID.randomUUID().toString().replace("-", "")
            val payload = JSONObject().put("tool", toolName).put("args", args).toString()
            val submitted = runCatching {
                scheduler.submitAll(listOf(SharedTaskScheduler.Task(
                    id = taskId,
                    title = "Voice tool: ${toolName.take(100)}",
                    priority = 2,
                    payload = payload
                ))).single()
            }.getOrElse { error ->
                return JSONObject().put("status", "error")
                    .put("message", error.message ?: "Main Brain could not queue the voice tool.")
            }
            if (scheduler.claimSpecific(taskId) == null) {
                runCatching { scheduler.cancel(taskId) }
                return JSONObject().put("status", "scheduler_busy")
                    .put("message", "Main Brain is executing a higher-priority task. Please retry after it finishes.")
                    .put("task_id", submitted.task.id)
            }
            return try {
                val result = com.Lia.assistant.action.ActionExecutor.execute(context.applicationContext, toolName, args, withinMainBrainTask = true)
                scheduler.complete(taskId)
                result
            } catch (cancelled: CancellationException) {
                runCatching { scheduler.cancel(taskId) }
                throw cancelled
            } catch (error: Exception) {
                runCatching { scheduler.fail(taskId, error.message ?: "Voice tool failed") }
                JSONObject().put("status", "error")
                    .put("message", error.localizedMessage ?: "Main Brain tool execution failed.")
            }
        }

        /** WorkManager remains the durable execution engine; all app enqueue requests enter here. */
        fun enqueueBackgroundCouncil(context: Context, goal: String, provider: String, language: String = "AUTO"): UUID =
            BackgroundTaskQueue.enqueueFromMainBrain(context.applicationContext, goal, provider, language)

        /** Background workers call back through Main Brain rather than selecting providers themselves. */
        fun executeBackgroundCouncil(
            context: Context,
            goal: String,
            language: String,
            provider: String,
            shouldContinue: () -> Boolean,
            onProgress: (String, Int, Int) -> Unit
        ): Result<CouncilExecutionReport> =
            AgentCouncil.executeWithConfiguredProvider(context.applicationContext, goal, language, provider, shouldContinue, onProgress)

        /** Website Forge is a first-class scheduled Main Brain task, not a direct provider bypass. */
        suspend fun forgeWebsite(
            context: Context,
            prompt: String,
            isEdit: Boolean,
            initialHtml: String?
        ): Boolean {
            val scheduler = MainBrainSchedulerRuntime.get(context.applicationContext)
            val taskId = "forge_" + UUID.randomUUID().toString().replace("-", "")
            val payload = JSONObject().put("kind", "website_forge")
                .put("edit", isEdit).put("prompt", prompt.take(6000)).toString()
            runCatching {
                scheduler.submitAll(listOf(SharedTaskScheduler.Task(
                    id = taskId,
                    title = "Website Forge: ${prompt.take(100)}",
                    priority = 3,
                    payload = payload
                )))
            }.getOrElse { return false }
            if (scheduler.claimSpecific(taskId) == null) {
                runCatching { scheduler.cancel(taskId) }
                return false
            }
            return try {
                com.Lia.assistant.forge.WebsiteForgeClient.forgeWebsite(
                    context.applicationContext, prompt, isEdit, initialHtml
                )
                if (com.Lia.assistant.forge.ForgeController.state.value.phase == com.Lia.assistant.forge.ForgePhase.FAILED) {
                    scheduler.fail(taskId, "Website Forge reported a failed build")
                    false
                } else {
                    scheduler.complete(taskId)
                    true
                }
            } catch (cancelled: CancellationException) {
                runCatching { scheduler.cancel(taskId) }
                throw cancelled
            } catch (error: Exception) {
                runCatching { scheduler.fail(taskId, error.message ?: "Website Forge failed") }
                false
            }
        }

        /** Consent-gated form extraction is serialized through the shared Main Brain scheduler. */
        fun extractForm(
            context: Context,
            image: android.graphics.Bitmap,
            formType: String,
            instruction: String = "",
            provider: String = "auto",
            cloudProcessingConsent: Boolean,
            personalDataConsent: Boolean = false
        ): Result<`in`.shraddha.ai.operator.FormExtractionResult> {
            val scheduler = MainBrainSchedulerRuntime.get(context.applicationContext)
            val taskId = "form_" + UUID.randomUUID().toString().replace("-", "")
            val payload = JSONObject().put("kind", "form_extraction")
                .put("formType", formType).put("provider", provider).toString()
            runCatching {
                scheduler.submitAll(listOf(SharedTaskScheduler.Task(
                    id = taskId,
                    title = "Form extraction: ${formType.take(40)}",
                    priority = 2,
                    payload = payload
                )))
            }.getOrElse { return Result.failure(it) }
            if (scheduler.claimSpecific(taskId) == null) {
                runCatching { scheduler.cancel(taskId) }
                return Result.failure(IllegalStateException("Main Brain is busy with a higher-priority task."))
            }
            return try {
                val result = `in`.shraddha.ai.operator.FormExtractionClient.extract(
                    context.applicationContext, image, formType, instruction, provider,
                    cloudProcessingConsent, personalDataConsent
                )
                if (result.isSuccess) scheduler.complete(taskId)
                else scheduler.fail(taskId, result.exceptionOrNull()?.message ?: "Form extraction failed")
                result
            } catch (error: Exception) {
                runCatching { scheduler.fail(taskId, error.message ?: "Form extraction failed") }
                Result.failure(error)
            }
        }

        /** Learned app automations run as a sub-step of the already scheduled Main Brain voice task. */
        fun runLearnedWorkflow(
            context: Context,
            profile: `in`.shraddha.ai.operator.learning.AppTrainingProfile,
            workflowName: String,
            values: Map<String, String>,
            confirmed: Boolean,
            resume: Boolean,
            onEvent: (`in`.shraddha.ai.operator.OperatorEvent) -> Unit
        ): Boolean =
            `in`.shraddha.ai.operator.learning.AdaptiveWorkflowRunner(context.applicationContext, onEvent)
                .run(profile, workflowName, values, confirmed, resume)
    }

    private val requests = linkedMapOf<String, WorkflowRequest>()
    private val bridges = linkedMapOf<String, TaskPlannerOperatorBridge>()
    private var activeWorkflowId: String? = null
    private val scheduler = context?.applicationContext?.let(MainBrainSchedulerRuntime::get)
        ?: SharedTaskScheduler(1)
    init {
        require(maxParallelWorkflows == 1) { "Android UI Operator currently supports one active workflow at a time" }
        scheduler.snapshot().filter { it.state == SharedTaskScheduler.State.PENDING }.forEach { snapshot ->
            decodeRequest(snapshot.task.payload)?.let { requests[it.id] = it }
        }
    }

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
                priority = request.priority,
                payload = encodeRequest(request)
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
        val claimed = scheduler.claimReadyMatching { requests.containsKey(it.task.id) }.firstOrNull()
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

    private fun encodeRequest(request: WorkflowRequest): String {
        val dependencies = JSONArray().apply { request.dependsOn.sorted().forEach { put(it) } }
        val actions = JSONArray().apply {
            request.actions.forEach { action ->
                put(JSONObject()
                    .put("id", action.id)
                    .put("title", action.title)
                    .put("priority", action.priority)
                    .put("estimatedMinutes", action.estimatedMinutes)
                    .put("risk", action.risk.name)
                    .put("requiresApproval", action.requiresApproval)
                    .put("dependsOn", JSONArray().apply { action.dependsOn.sorted().forEach { put(it) } })
                    .put("step", JSONObject()
                        .put("action", action.step.action.name)
                        .put("target", action.step.target)
                        .put("value", action.step.value)
                        .put("expectedVisibleText", action.step.expectedVisibleText)
                        .put("requiresConfirmation", action.step.requiresConfirmation)))
            }
        }
        return JSONObject().put("id", request.id).put("title", request.title)
            .put("priority", request.priority).put("dependsOn", dependencies)
            .put("actions", actions).toString()
    }

    private fun decodeRequest(payload: String): WorkflowRequest? = runCatching {
        val json = JSONObject(payload)
        val dependencies = json.getJSONArray("dependsOn")
        val actions = json.getJSONArray("actions")
        val decoded = (0 until actions.length()).map { index ->
            val item = actions.getJSONObject(index)
            val step = item.getJSONObject("step")
            val actionDependencies = item.getJSONArray("dependsOn")
            PlannedOperatorAction(
                id = item.getString("id"),
                title = item.getString("title"),
                step = `in`.shraddha.ai.operator.OperatorStep(
                    action = `in`.shraddha.ai.operator.OperatorActionType.valueOf(step.getString("action")),
                    target = step.optString("target"),
                    value = step.optString("value"),
                    expectedVisibleText = step.optString("expectedVisibleText"),
                    requiresConfirmation = step.optBoolean("requiresConfirmation")
                ),
                dependsOn = (0 until actionDependencies.length()).map { actionDependencies.getString(it) }.toSet(),
                priority = item.optInt("priority", 3),
                estimatedMinutes = item.optInt("estimatedMinutes", 1),
                risk = Risk.valueOf(item.optString("risk", Risk.READ_ONLY.name)),
                requiresApproval = item.optBoolean("requiresApproval")
            )
        }
        WorkflowRequest(
            id = json.getString("id"),
            title = json.getString("title"),
            actions = decoded,
            priority = json.optInt("priority", 3),
            dependsOn = (0 until dependencies.length()).map { dependencies.getString(it) }.toSet()
        )
    }.getOrNull()

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
