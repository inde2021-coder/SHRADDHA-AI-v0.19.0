package `in`.shraddha.ai.future

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Resumable-by-WorkManager background execution of the provider-backed five-role team. */
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

        val result = withContext(Dispatchers.IO) {
            AgentCouncil.executeWithProvider(applicationContext, goal, language, provider) { role, index, total ->
                if (!isStopped) runBlocking {
                    setProgress(workDataOf(
                        BackgroundTaskQueue.KEY_PROGRESS_ROLE to role,
                        BackgroundTaskQueue.KEY_PROGRESS_INDEX to index,
                        BackgroundTaskQueue.KEY_PROGRESS_TOTAL to total
                    ))
                }
            }
        }
        if (isStopped) return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to "Task cancelled; partial output is not marked complete."))
        return result.fold(
            onSuccess = { report ->
                // WorkManager Data is limited to 10 KB; keep only the verifier's final report.
                Result.success(workDataOf(BackgroundTaskQueue.KEY_FINAL_REPORT to report.finalAnswer.take(7000)))
            },
            onFailure = { error ->
                Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to (error.message ?: "Multi-agent execution failed").take(1000)))
            }
        )
    }
}
