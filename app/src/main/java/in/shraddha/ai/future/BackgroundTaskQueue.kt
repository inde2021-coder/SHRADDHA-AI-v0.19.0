package `in`.shraddha.ai.future

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import org.json.JSONObject

/** Persistent, network-aware queue for user-requested multi-agent work. */
object BackgroundTaskQueue {
    const val TAG = "shraddha-background-agent"
    const val KEY_MAIN_BRAIN_TASK = "main_brain_task"
    const val KEY_SCHEDULER_TASK_ID = "scheduler_task_id"
    const val KEY_GOAL = "goal"
    const val KEY_PROVIDER = "provider"
    const val KEY_LANGUAGE = "language"
    const val KEY_SPECIALIST_COUNT = "specialist_count"
    const val KEY_MAX_PROVIDER_CALLS = "max_provider_calls"
    const val KEY_MAX_CALLS_PER_PROVIDER = "max_calls_per_provider"
    const val KEY_MAX_DURATION_MS = "max_duration_ms"
    const val KEY_MAX_OUTPUT_CHARS = "max_output_chars"
    const val KEY_PROGRESS_ROLE = "progress_role"
    const val KEY_PROGRESS_INDEX = "progress_index"
    const val KEY_PROGRESS_TOTAL = "progress_total"
    const val KEY_FINAL_REPORT = "final_report"
    const val KEY_ERROR = "error"

    fun enqueue(
        context: Context,
        goal: String,
        provider: String,
        language: String = "AUTO",
        specialistCount: Int = 3,
        budget: CouncilExecutionBudget = CouncilExecutionBudget()
    ): UUID = MainBrain.enqueueBackgroundCouncil(context, goal, provider, language, specialistCount, budget)

    internal fun enqueueFromMainBrain(
        context: Context,
        goal: String,
        provider: String,
        language: String = "AUTO",
        specialistCount: Int = 3,
        budget: CouncilExecutionBudget = CouncilExecutionBudget()
    ): UUID {
        val cleanGoal = goal.trim()
        require(cleanGoal.isNotBlank() && cleanGoal.length <= 2000) { "Task must contain 1-2000 characters" }
        require(specialistCount in 1..5) { "Choose between one and five specialist agents" }
        require(budget.maxProviderCalls in (specialistCount + 4)..9) { "Provider-call budget must cover every council stage." }
        require(budget.maxCallsPerProvider in 1..9) { "Per-provider call budget must be between one and nine." }
        require(budget.maxDurationMs in 10_000..600_000) { "Council time budget must be between 10 seconds and 10 minutes." }
        require(budget.maxOutputCharactersPerStage in 1_000..9_000) { "Stage output limit must be between 1,000 and 9,000 characters." }
        val providers = provider.split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        require(providers.isNotEmpty() && providers.size <= 5 && providers.all {
            it in setOf("GEMINI", "OPENAI", "GROQ", "CLAUDE", "PERPLEXITY")
        }) { "Choose between one and five supported AI providers (comma-separated)" }
        val workId = UUID.randomUUID()
        val schedulerTaskId = "bg_" + workId.toString().replace("-", "")
        val scheduler = MainBrainSchedulerRuntime.get(context.applicationContext)
        scheduler.submitAll(listOf(
            SharedTaskScheduler.Task(
                id = schedulerTaskId,
                title = cleanGoal.take(160),
                priority = 5,
                payload = JSONObject().put("kind", "background_council")
                    .put(KEY_GOAL, cleanGoal)
                    .put(KEY_PROVIDER, providers.joinToString(","))
                    .put(KEY_LANGUAGE, language.take(20))
                    .put(KEY_SPECIALIST_COUNT, specialistCount)
                    .put(KEY_MAX_PROVIDER_CALLS, budget.maxProviderCalls)
                    .put(KEY_MAX_CALLS_PER_PROVIDER, budget.maxCallsPerProvider)
                    .put(KEY_MAX_DURATION_MS, budget.maxDurationMs)
                    .put(KEY_MAX_OUTPUT_CHARS, budget.maxOutputCharactersPerStage).toString()
            )
        ))
        val request = OneTimeWorkRequestBuilder<BackgroundAgentWorker>()
            .setId(workId)
            .setInputData(workDataOf(
                KEY_MAIN_BRAIN_TASK to true,
                KEY_SCHEDULER_TASK_ID to schedulerTaskId
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        try {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "shraddha-agent-${request.id}", ExistingWorkPolicy.KEEP, request
            )
        } catch (error: Exception) {
            runCatching { scheduler.cancel(schedulerTaskId) }
            throw error
        }
        return workId
    }

    fun cancel(context: Context, id: UUID) {
        WorkManager.getInstance(context.applicationContext).cancelWorkById(id)
        val taskId = "bg_" + id.toString().replace("-", "")
        val scheduler = MainBrainSchedulerRuntime.get(context.applicationContext)
        val state = scheduler.get(taskId)?.state
        if (state == SharedTaskScheduler.State.PENDING || state == SharedTaskScheduler.State.RUNNING) {
            scheduler.cancel(taskId)
        }
    }
}
