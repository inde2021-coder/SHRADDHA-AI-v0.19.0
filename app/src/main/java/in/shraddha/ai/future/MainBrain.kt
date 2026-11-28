package `in`.shraddha.ai.future

import `in`.shraddha.ai.operator.PlannedOperatorAction
import `in`.shraddha.ai.operator.TaskPlannerOperatorBridge
import `in`.shraddha.ai.operator.OperatorStep
import `in`.shraddha.ai.operator.OperatorActionJournal
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
    private val appContext = context?.applicationContext
    private val actionJournal = appContext?.let(::OperatorActionJournal)
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
            title: String = goal.trim().take(120),
            context: Context? = null
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
            val session = ShraddhaIntegrationHub(context = context).createPlan(
                workflowId = workflowId,
                title = title.ifBlank { "Shraddha AI Council plan" },
                actions = actions
            )
            return council to session
        }

        /**
         * Voice device/app tools are serialized through the durable scheduler and a write-ahead
         * command journal. Startup converts interrupted RUNNING actions to UNCERTAIN; they are
         * never blindly replayed, and corrupted journal state blocks new tool execution.
         */
        suspend fun executeVoiceTool(context: Context, toolName: String, args: JSONObject): JSONObject {
            if (!`in`.shraddha.ai.operator.CommandExecutionJournalStartup.isReady()) {
                return JSONObject().put("status", "blocked")
                    .put("action", "command_journal_recovery_required")
                    .put("message", "सुरक्षित command history की recovery पूरी नहीं हुई। कोई device action शुरू नहीं किया गया।")
            }

            val commandJournal = `in`.shraddha.ai.operator.CommandExecutionJournal(context.applicationContext)
            val plan = runCatching {
                commandJournal.createPlan(
                    toolName + ":" + args.toString(),
                    listOf(`in`.shraddha.ai.operator.CommandTaskStep("voice-action", "voice_tool", toolName))
                )
            }.getOrNull() ?: return JSONObject().put("status", "blocked")
                .put("action", "command_journal_unavailable")
                .put("message", "सुरक्षित command record नहीं बन पाया; कोई action execute नहीं किया गया।")
            fun safeFinish(state: `in`.shraddha.ai.operator.CommandStepState, code: String) =
                runCatching { commandJournal.finish(plan.id, "voice-action", state, code) }.getOrNull()

            if (runCatching { commandJournal.markRunning(plan.id, "voice-action") }.getOrNull() == null) {
                return JSONObject().put("status", "blocked")
                    .put("action", "duplicate_or_unresolved_command")
                    .put("message", "यह command पहले से दर्ज है या इसकी पिछली स्थिति unresolved है। दोबारा चलाने से पहले target app में जाँच करें।")
            }

            fun recordResult(result: JSONObject): JSONObject {
                val status = result.optString("status", "unknown").lowercase()
                val state = when (status) {
                    "success", "queued" -> `in`.shraddha.ai.operator.CommandStepState.SUCCEEDED
                    "needs_user_action", "confirmation_required", "needs_clarification" ->
                        `in`.shraddha.ai.operator.CommandStepState.BLOCKED
                    // A tool-level error can occur after a remote/device side effect has begun.
                    // Keep it non-retryable until an adapter proves that nothing was dispatched.
                    "error", "failed", "verification_pending" ->
                        `in`.shraddha.ai.operator.CommandStepState.UNCERTAIN
                    else -> `in`.shraddha.ai.operator.CommandStepState.UNCERTAIN
                }
                val saved = safeFinish(state, result.optString("action", status))
                return if (saved != null) result else JSONObject()
                    .put("status", "verification_pending")
                    .put("action", "command_result_not_journaled")
                    .put("message", "Action का result सुरक्षित रूप से दर्ज नहीं हुआ। इसे दोबारा अपने-आप नहीं चलाया जाएगा; target app में जाँच करें।")
            }

            // Website Forge creates its own long-running Main Brain scheduler task after
            // validating/opening the Forge UI. Do not occupy a parent slot and then deadlock
            // while the Forge task claims the same single-worker scheduler.
            if (toolName == "build_website") {
                val journal = `in`.shraddha.ai.operator.VoiceToolActionJournal(context.applicationContext)
                val taskId = "voice_" + UUID.randomUUID().toString().replace("-", "")
                if (!journal.begin(taskId, toolName)) {
                    safeFinish(`in`.shraddha.ai.operator.CommandStepState.BLOCKED, "voice_journal_busy")
                    return JSONObject().put("status", "error")
                        .put("action", "voice_action_journal_unavailable")
                        .put("message", "सुरक्षित action journal save नहीं हुई; Website Forge launch नहीं किया गया।")
                }
                val result = runCatching {
                    com.Lia.assistant.action.ActionExecutor.execute(context.applicationContext, toolName, args)
                }.getOrElse { error ->
                    JSONObject().put("status", "error")
                        .put("action", "website_forge_launch_failed")
                        .put("message", error.localizedMessage ?: "Website Forge launch failed.")
                }
                if (!journal.finish(taskId, result)) {
                    safeFinish(`in`.shraddha.ai.operator.CommandStepState.UNCERTAIN, "voice_result_unrecorded")
                    return JSONObject()
                        .put("status", "verification_pending")
                        .put("action", "voice_action_result_not_journaled")
                        .put("message", "Website Forge launch का result सुरक्षित रूप से save नहीं हुआ। दोबारा launch करने से पहले Forge की स्थिति जाँचें।")
                }
                return recordResult(result)
            }

            val scheduler = runCatching { MainBrainSchedulerRuntime.get(context.applicationContext) }
                .getOrElse { error ->
                    safeFinish(`in`.shraddha.ai.operator.CommandStepState.BLOCKED, "scheduler_unavailable")
                    return JSONObject().put("status", "error")
                        .put("action", "scheduler_storage_unavailable")
                        .put("message", "Main Brain encrypted task history could not be opened; no voice action was started. ${error.localizedMessage ?: "Secure storage unavailable"}")
                }
            val taskId = "voice_" + UUID.randomUUID().toString().replace("-", "")
            val payload = JSONObject().put("kind", "voice_tool").put("tool", toolName).put("args", args).toString()
            val submitted = runCatching {
                scheduler.submitAll(listOf(SharedTaskScheduler.Task(
                    id = taskId,
                    title = "Voice tool: ${toolName.take(100)}",
                    priority = 2,
                    payload = payload
                ))).single()
            }.getOrElse { error ->
                safeFinish(`in`.shraddha.ai.operator.CommandStepState.BLOCKED, "scheduler_submit_failed")
                return JSONObject().put("status", "error")
                    .put("message", error.message ?: "Main Brain could not queue the voice tool.")
            }
            val claim = runCatching { scheduler.claimSpecific(taskId) }.getOrElse { error ->
                safeFinish(`in`.shraddha.ai.operator.CommandStepState.BLOCKED, "scheduler_claim_failed")
                return JSONObject().put("status", "error")
                    .put("action", "scheduler_claim_failed")
                    .put("message", error.localizedMessage ?: "Main Brain could not safely claim the voice action.")
            }
            if (claim == null) {
                runCatching { scheduler.cancel(taskId) }
                safeFinish(`in`.shraddha.ai.operator.CommandStepState.BLOCKED, "scheduler_busy")
                return JSONObject().put("status", "scheduler_busy")
                    .put("message", "Main Brain is executing a higher-priority task. Please retry after it finishes.")
                    .put("task_id", submitted.task.id)
            }
            val journal = `in`.shraddha.ai.operator.VoiceToolActionJournal(context.applicationContext)
            if (!journal.begin(taskId, toolName)) {
                runCatching { scheduler.fail(taskId, "Encrypted voice action journal could not be committed; action was not executed.") }
                safeFinish(`in`.shraddha.ai.operator.CommandStepState.BLOCKED, "voice_journal_busy")
                return JSONObject().put("status", "error")
                    .put("action", "voice_action_journal_unavailable")
                    .put("message", "सुरक्षित action journal save नहीं हुई; कोई action execute नहीं किया गया।")
            }
            return try {
                val result = com.Lia.assistant.action.ActionExecutor.execute(context.applicationContext, toolName, args, withinMainBrainTask = true)
                if (!journal.finish(taskId, result)) {
                    runCatching { scheduler.fail(taskId, "Action result could not be durably journaled; automatic replay is prohibited.") }
                    safeFinish(`in`.shraddha.ai.operator.CommandStepState.UNCERTAIN, "voice_result_unrecorded")
                    JSONObject().put("status", "verification_pending")
                        .put("action", "voice_action_result_not_journaled")
                        .put("message", "Action execute हो सकता है, लेकिन उसका result सुरक्षित रूप से save नहीं हुआ। इसे दोबारा अपने-आप नहीं चलाया जाएगा; target app में जाँच करें।")
                } else {
                    val recorded = recordResult(result)
                    if (recorded.optString("status") == "verification_pending") {
                        runCatching { scheduler.fail(taskId, "Action result could not be durably journaled; review required.") }
                    } else {
                        scheduler.complete(taskId)
                    }
                    recorded
                }
            } catch (cancelled: CancellationException) {
                // Leave the write-ahead record RUNNING. Process-start recovery will mark it UNCERTAIN.
                runCatching { scheduler.fail(taskId, "Voice action interrupted; journal remains for manual review.") }
                throw cancelled
            } catch (error: Exception) {
                val failed = JSONObject().put("status", "error")
                    .put("action", "voice_tool_failed")
                    .put("message", error.localizedMessage ?: "Main Brain tool execution failed.")
                journal.finish(taskId, failed)
                safeFinish(`in`.shraddha.ai.operator.CommandStepState.UNCERTAIN, "execution_interrupted")
                runCatching { scheduler.fail(taskId, "Voice tool may have partially executed; review required.") }
                failed
            }
        }

        /** WorkManager remains the durable execution engine; all app enqueue requests enter here. */
        fun enqueueBackgroundCouncil(
            context: Context,
            goal: String,
            provider: String,
            language: String = "AUTO",
            specialistCount: Int = 3,
            budget: CouncilExecutionBudget = CouncilExecutionBudget()
        ): UUID = BackgroundTaskQueue.enqueueFromMainBrain(
            context.applicationContext, goal, provider, language, specialistCount, budget
        )

        /** Background workers call back through Main Brain rather than selecting providers themselves. */
        suspend fun executeBackgroundCouncil(
            context: Context,
            goal: String,
            language: String,
            provider: String,
            specialistCount: Int = 3,
            shouldContinue: () -> Boolean,
            onProgress: (String, Int, Int) -> Unit,
            onStageCompleted: (String, String, String) -> Unit = { _, _, _ -> },
            resumeStages: Map<String, CouncilSavedStage> = emptyMap(),
            budget: CouncilExecutionBudget = CouncilExecutionBudget(),
            onProviderCall: (String, Int, Int, Long) -> Unit = { _, _, _, _ -> },
            onTokenUsage: (com.Lia.assistant.voice.ProviderTokenUsage) -> Unit = { }
        ): Result<CouncilExecutionReport> =
            AgentCouncil.executeParallelWithConfiguredProviders(
                context = context.applicationContext,
                goal = goal,
                language = language,
                preferredProviders = provider.split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() },
                specialistCount = specialistCount,
                shouldContinue = shouldContinue,
                onProgress = onProgress,
                onStageCompleted = onStageCompleted,
                resumeStages = resumeStages,
                budget = budget,
                onProviderCall = onProviderCall,
                onTokenUsage = onTokenUsage
            )

        /** Website Forge is a first-class scheduled Main Brain task, not a direct provider bypass. */
        suspend fun forgeWebsite(
            context: Context,
            prompt: String,
            isEdit: Boolean,
            initialHtml: String?
        ): Boolean {
            val scheduler = runCatching { MainBrainSchedulerRuntime.get(context.applicationContext) }.getOrElse { return false }
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
            val claim = runCatching { scheduler.claimSpecific(taskId) }.getOrElse { return false }
            if (claim == null) {
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
            val scheduler = runCatching { MainBrainSchedulerRuntime.get(context.applicationContext) }
                .getOrElse { return Result.failure(it) }
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
            val claim = runCatching { scheduler.claimSpecific(taskId) }.getOrElse { return Result.failure(it) }
            if (claim == null) {
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

        /** Learned app automations use the active voice task slot or claim their own shared slot. */
        fun runLearnedWorkflow(
            context: Context,
            profile: `in`.shraddha.ai.operator.learning.AppTrainingProfile,
            workflowName: String,
            values: Map<String, String>,
            confirmed: Boolean,
            resume: Boolean,
            withinMainBrainTask: Boolean = false,
            onEvent: (`in`.shraddha.ai.operator.OperatorEvent) -> Unit
        ): Boolean {
            val appContext = context.applicationContext
            if (withinMainBrainTask) {
                return runCatching {
                    `in`.shraddha.ai.operator.learning.AdaptiveWorkflowRunner(appContext, onEvent)
                        .run(profile, workflowName, values, confirmed, resume)
                }.getOrDefault(false)
            }
            val scheduler = runCatching { MainBrainSchedulerRuntime.get(appContext) }.getOrElse { return false }
            val taskId = "learned_" + UUID.randomUUID().toString().replace("-", "")
            val payload = JSONObject().put("kind", "learned_workflow")
                .put("package", profile.packageName)
                .put("workflow", workflowName.take(120))
                .put("resume", resume).toString()
            runCatching {
                scheduler.submitAll(listOf(SharedTaskScheduler.Task(
                    id = taskId,
                    title = "Learned workflow: ${workflowName.take(100)}",
                    priority = 2,
                    payload = payload
                )))
            }.getOrElse { return false }
            val claim = runCatching { scheduler.claimSpecific(taskId) }.getOrElse { return false }
            if (claim == null) {
                runCatching { scheduler.cancel(taskId) }
                return false
            }
            return try {
                val completed = `in`.shraddha.ai.operator.learning.AdaptiveWorkflowRunner(appContext, onEvent)
                    .run(profile, workflowName, values, confirmed, resume)
                if (completed) scheduler.complete(taskId)
                else scheduler.fail(taskId, "Learned workflow paused or failed; resume only from its verified checkpoint.")
                completed
            } catch (cancelled: CancellationException) {
                runCatching { scheduler.cancel(taskId) }
                throw cancelled
            } catch (error: Exception) {
                runCatching { scheduler.fail(taskId, error.message ?: "Learned workflow failed") }
                false
            }
        }
    }

    private val requests = linkedMapOf<String, WorkflowRequest>()
    private val bridges = linkedMapOf<String, TaskPlannerOperatorBridge>()
    private var activeWorkflowId: String? = null
    private val scheduler = appContext?.let(MainBrainSchedulerRuntime::get)
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
            val decision = OperatorSafetyPolicy.assess(action.step.action.name, action.step.target, action.step.value, action.step.expectedVisibleText)
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
        val bridge = TaskPlannerOperatorBridge(
            executeVerified = executeVerified,
            audit = audit,
            beforeExecute = { workflowId, action ->
                // Durable write-ahead journal is mandatory for app-backed Operator execution.
                actionJournal?.begin(workflowId, action) ?: (appContext == null)
            },
            afterExecute = { workflowId, action, verified ->
                actionJournal?.finish(workflowId, action, verified) ?: (appContext == null)
            }
        )
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
                actionJournal?.clear(id)
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

    data class InterruptedWorkflowReview(
        val workflowId: String,
        val title: String,
        val stepId: String,
        val action: String,
        val packageName: String,
        val recovery: OperatorActionJournal.Recovery
    )

    /** Reconcile interrupted Operator actions after process restart; this method never replays. */
    @Synchronized
    fun reviewInterruptedWorkflows(): List<InterruptedWorkflowReview> {
        val journal = actionJournal ?: return emptyList()
        return scheduler.snapshot()
            .filter { it.state == SharedTaskScheduler.State.FAILED }
            .mapNotNull { snapshot ->
                val entry = journal.getResult(snapshot.task.id).getOrElse { error ->
                    audit("operator_recovery", "journal_unreadable",
                        "workflow=" + snapshot.task.id + "; reason=" + (error.javaClass.simpleName))
                    return@mapNotNull InterruptedWorkflowReview(
                        snapshot.task.id, snapshot.task.title, "unknown",
                        "journal_unreadable", "unknown (encrypted journal unavailable)",
                        OperatorActionJournal.Recovery.REVIEW_REQUIRED
                    )
                } ?: return@mapNotNull null
                if (entry.state !in setOf("EXECUTING", "FAILED")) return@mapNotNull null
                val recovery = journal.reconcileInterrupted(snapshot.task.id)
                audit("operator_recovery", recovery.name.lowercase(),
                    "workflow=" + snapshot.task.id + "; step=" + entry.stepId + "; action=" + entry.action)
                InterruptedWorkflowReview(snapshot.task.id, snapshot.task.title,
                    entry.stepId, entry.action, entry.packageName, recovery)
            }
    }

    /** Enqueue only steps after a journal-verified or explicitly user-attested action; never replays it. */
    @Synchronized
    fun resumeInterruptedWorkflow(workflowId: String, manuallyVerified: Boolean = false): SharedTaskScheduler.Snapshot? {
        val journal = actionJournal ?: return null
        val original = scheduler.get(workflowId) ?: return null
        if (original.state != SharedTaskScheduler.State.FAILED) return null
        if (manuallyVerified && !journal.markManuallyVerified(workflowId)) return null
        val recovery = journal.reconcileInterrupted(workflowId)
        if (recovery !in setOf(
                OperatorActionJournal.Recovery.VERIFIED_COMPLETE,
                OperatorActionJournal.Recovery.MANUALLY_VERIFIED
            )) return null
        val entry = journal.getResult(workflowId).getOrNull() ?: return null
        val request = decodeRequest(original.task.payload) ?: return null
        val completedIndex = request.actions.indexOfFirst { it.id == entry.stepId }
        if (completedIndex < 0) return null
        val remaining = request.actions.drop(completedIndex + 1)
        if (remaining.isEmpty()) {
            val completed = scheduler.completeInterrupted(workflowId, verified = true)
            journal.clear(workflowId)
            audit("operator_recovery", "workflow_completed_from_journal",
                "workflow=" + workflowId + "; last_step=" + entry.stepId)
            return completed
        }
        val remainingIds = remaining.map { it.id }.toSet()
        val safeRemaining = remaining.map { action ->
            action.copy(dependsOn = action.dependsOn.intersect(remainingIds))
        }
        val resumed = WorkflowRequest(
            id = "resume_" + UUID.randomUUID().toString().replace("-", ""),
            title = request.title.take(120) + " (resume)",
            actions = safeRemaining,
            priority = request.priority
        )
        audit("operator_recovery", "resume_enqueued",
            "original=" + workflowId + "; verified_step=" + entry.stepId + "; remaining=" + safeRemaining.size)
        return enqueueOperatorWorkflow(resumed)
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
                actionJournal?.clear(id)
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
