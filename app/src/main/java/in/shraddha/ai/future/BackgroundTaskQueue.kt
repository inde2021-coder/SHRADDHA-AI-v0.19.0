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
    const val KEY_PROGRESS_ROLE = "progress_role"
    const val KEY_PROGRESS_INDEX = "progress_index"
    const val KEY_PROGRESS_TOTAL = "progress_total"
    const val KEY_FINAL_REPORT = "final_report"
    const val KEY_ERROR = "error"

    fun enqueue(context: Context, goal: String, provider: String, language: String = "AUTO"): UUID =
        MainBrain.enqueueBackgroundCouncil(context, goal, provider, language)

    internal fun enqueueFromMainBrain(context: Context, goal: String, provider: String, language: String = "AUTO"): UUID {
        val cleanGoal = goal.trim()
        require(cleanGoal.isNotBlank() && cleanGoal.length <= 2000) { "Task must contain 1-2000 characters" }
        require(provider.uppercase() in setOf("GEMINI", "OPENAI", "GROQ", "CLAUDE", "PERPLEXITY")) {
            "Unsupported AI provider"
        }
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
                    .put(KEY_PROVIDER, provider.uppercase())
                    .put(KEY_LANGUAGE, language.take(20)).toString()
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
