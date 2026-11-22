package `in`.shraddha.ai.future

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** WorkManager adapter for the shared Main Brain scheduler and provider council. */
class BackgroundAgentWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val goal = inputData.getString(BackgroundTaskQueue.KEY_GOAL).orEmpty()
        val provider = inputData.getString(BackgroundTaskQueue.KEY_PROVIDER).orEmpty()
        val language = inputData.getString(BackgroundTaskQueue.KEY_LANGUAGE) ?: "AUTO"
        if (goal.isBlank() || provider.isBlank()) {
            return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to "Background task input is incomplete."))
        }
        if (isStopped) return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to "Task cancelled before it started."))
        val schedulerTaskId = inputData.getString(BackgroundTaskQueue.KEY_SCHEDULER_TASK_ID)
            ?: return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to "Main Brain scheduler task ID is missing."))
        val scheduler = MainBrainSchedulerRuntime.get(applicationContext)
        // WorkManager may restart this worker after process death. Council work performs
        // provider reasoning only, so it is safe to retry; device/voice actions are not.
        scheduler.requeueInterrupted(schedulerTaskId) { task ->
            runCatching { org.json.JSONObject(task.payload).optString("kind") == "background_council" }
                .getOrDefault(false)
        }
        val claimed = scheduler.claimSpecific(schedulerTaskId)
        if (claimed == null) {
            val current = scheduler.get(schedulerTaskId)
            return if (current?.state == SharedTaskScheduler.State.PENDING ||
                current?.state == SharedTaskScheduler.State.RUNNING) Result.retry()
            else Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to
                (current?.error ?: "Task is not claimable in the shared Main Brain scheduler.")))
        }

        val result = withContext(Dispatchers.IO) {
            MainBrain.executeBackgroundCouncil(applicationContext, goal, language, provider, shouldContinue = { !isStopped }, onProgress = { role, index, total ->
                if (!isStopped) setProgressAsync(workDataOf(
                    BackgroundTaskQueue.KEY_PROGRESS_ROLE to role,
                    BackgroundTaskQueue.KEY_PROGRESS_INDEX to index,
                    BackgroundTaskQueue.KEY_PROGRESS_TOTAL to total
                ))
            })
        }
        if (isStopped) {
            runCatching { scheduler.cancel(schedulerTaskId) }
            return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to "Task cancelled; partial output is not marked complete."))
        }
        return result.fold(
            onSuccess = { report ->
                scheduler.complete(schedulerTaskId)
                // WorkManager Data is limited to 10 KB; keep only the verifier's final report.
                Result.success(workDataOf(BackgroundTaskQueue.KEY_FINAL_REPORT to report.finalAnswer.take(1800)))
            },
            onFailure = { error ->
                scheduler.fail(schedulerTaskId, (error.message ?: "Multi-agent execution failed").take(500))
                Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to (error.message ?: "Multi-agent execution failed").take(1000)))
            }
        )
    }
}
