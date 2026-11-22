package `in`.shraddha.ai.future

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** WorkManager adapter for the shared Main Brain scheduler and provider council. */
class BackgroundAgentWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (isStopped) return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to "Task cancelled before it started."))
        val schedulerTaskId = inputData.getString(BackgroundTaskQueue.KEY_SCHEDULER_TASK_ID)
            ?: return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to "Main Brain scheduler task ID is missing."))
        val scheduler = MainBrainSchedulerRuntime.get(applicationContext)
        val evidence = CouncilEvidenceStore(applicationContext)
        val attempt = runAttemptCount.coerceIn(0, 99)

        // WorkManager may restart this provider-reasoning task after process death.
        // Previous stage evidence is retained encrypted; retries are a new attempt and
        // never mark an incomplete run as completed.
        scheduler.requeueInterrupted(schedulerTaskId) { task ->
            runCatching { JSONObject(task.payload).optString("kind") == "background_council" }
                .getOrDefault(false)
        }
        val claimed = scheduler.claimSpecific(schedulerTaskId)
        if (claimed == null) {
            val current = scheduler.get(schedulerTaskId)
            return if (current?.state == SharedTaskScheduler.State.PENDING || current?.state == SharedTaskScheduler.State.RUNNING) {
                Result.retry()
            } else {
                Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to
                    (current?.error ?: "Task is not claimable in the shared Main Brain scheduler.")))
            }
        }

        val payload = runCatching { JSONObject(claimed.task.payload) }.getOrNull()
        val goal = payload?.optString(BackgroundTaskQueue.KEY_GOAL).orEmpty()
        val provider = payload?.optString(BackgroundTaskQueue.KEY_PROVIDER).orEmpty()
        val language = payload?.optString(BackgroundTaskQueue.KEY_LANGUAGE) ?: "AUTO"
        val specialistCount = payload?.optInt(BackgroundTaskQueue.KEY_SPECIALIST_COUNT, 3) ?: 3
        if (goal.isBlank() || provider.isBlank()) {
            val message = "Encrypted background task payload is incomplete."
            scheduler.fail(schedulerTaskId, message)
            evidence.saveStatus(schedulerTaskId, "FAILED", message, attempt = attempt)
            return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to message))
        }

        evidence.saveStatus(
            schedulerTaskId,
            "RUNNING",
            "Attempt ${attempt + 1}: provider council started. Completed stages are saved separately.",
            attempt = attempt
        ).getOrThrow()

        val result = withContext(Dispatchers.IO) {
            MainBrain.executeBackgroundCouncil(
                context = applicationContext,
                goal = goal,
                language = language,
                provider = provider,
                specialistCount = specialistCount,
                shouldContinue = { !isStopped },
                onProgress = { role, index, total ->
                    if (!isStopped) {
                        setProgressAsync(workDataOf(
                            BackgroundTaskQueue.KEY_PROGRESS_ROLE to role,
                            BackgroundTaskQueue.KEY_PROGRESS_INDEX to index,
                            BackgroundTaskQueue.KEY_PROGRESS_TOTAL to total
                        ))
                        evidence.saveStatus(
                            schedulerTaskId,
                            "RUNNING",
                            "Attempt ${attempt + 1}: executing $role ($index/$total).",
                            attempt = attempt
                        ).getOrThrow()
                    }
                },
                onStageCompleted = { role, stageProvider, output ->
                    evidence.saveStage(
                        taskId = schedulerTaskId,
                        goal = goal,
                        role = role,
                        provider = stageProvider,
                        output = output,
                        attempt = attempt
                    ).getOrThrow()
                    evidence.saveStatus(
                        schedulerTaskId,
                        "RUNNING",
                        "Attempt ${attempt + 1}: $role output saved securely; next stage pending.",
                        attempt = attempt
                    ).getOrThrow()
                }
            )
        }

        if (isStopped) {
            runCatching { scheduler.cancel(schedulerTaskId) }
            runCatching {
                evidence.saveStatus(
                    schedulerTaskId,
                    "CANCELLED",
                    "Task cancelled. Previously completed stage evidence is retained; the task is not marked complete.",
                    attempt = attempt
                )
            }
            return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to "Task cancelled; partial output is not marked complete."))
        }

        return result.fold(
            onSuccess = { report ->
                val saved = evidence.saveStatus(
                    schedulerTaskId,
                    "COMPLETED",
                    "Planner, Researcher, Specialists, Critic and Verifier finished. Final answer saved.",
                    finalAnswer = report.finalAnswer,
                    attempt = attempt,
                    providerUsage = report.providerUsage.joinToString(",") { "${it.provider}:${it.calls}" },
                    elapsedMs = report.elapsedMs
                )
                if (saved.isFailure) {
                    val message = "Council finished, but its final evidence status could not be saved securely."
                    scheduler.fail(schedulerTaskId, message)
                    Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to message))
                } else {
                    scheduler.complete(schedulerTaskId)
                    // WorkManager Data is limited to 10 KB; keep only a short verifier report.
                    Result.success(workDataOf(BackgroundTaskQueue.KEY_FINAL_REPORT to report.finalAnswer.take(1800)))
                }
            },
            onFailure = { error ->
                val message = (error.message ?: "Multi-agent execution failed").take(1000)
                val retryable = error is java.io.IOException ||
                    Regex("(?i)(\\b429\\b|\\b5[0-9]{2}\\b|timeout|timed out|temporarily unavailable)").containsMatchIn(message)
                if (retryable && runAttemptCount < 2) {
                    evidence.saveStatus(
                        schedulerTaskId,
                        "RETRY_PENDING",
                        "Attempt ${attempt + 1} failed and will retry: $message. Partial stage evidence is retained.",
                        attempt = attempt
                    ).getOrThrow()
                    Result.retry()
                } else {
                    evidence.saveStatus(
                        schedulerTaskId,
                        "FAILED",
                        "Council stopped without a verified final result: $message",
                        attempt = attempt
                    ).getOrThrow()
                    scheduler.fail(schedulerTaskId, message.take(500))
                    Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to message))
                }
            }
        )
    }
}
