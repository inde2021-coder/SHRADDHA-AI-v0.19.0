package `in`.shraddha.ai.future

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/** WorkManager adapter for the shared Main Brain scheduler and provider council. */
class BackgroundAgentWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (isStopped) return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to "Task cancelled before it started."))
        val schedulerTaskId = inputData.getString(BackgroundTaskQueue.KEY_SCHEDULER_TASK_ID)
            ?: return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to "Main Brain scheduler task ID is missing."))
        val scheduler = try {
            MainBrainSchedulerRuntime.get(applicationContext)
        } catch (error: Exception) {
            val message = "Main Brain encrypted scheduler could not be restored; task was not executed: ${error.localizedMessage ?: error.javaClass.simpleName}"
            return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to message.take(1000)))
        }
        val evidence = CouncilEvidenceStore(applicationContext)
        val attempt = runAttemptCount.coerceIn(0, 99)
        val usageLock = Any()
        val usageCounts = linkedMapOf<String, Int>()
        val tokenUsageRecords = java.util.Collections.synchronizedList(
            mutableListOf<com.Lia.assistant.voice.ProviderTokenUsage>()
        )

        // WorkManager may restart this provider-reasoning task after process death.
        // Previous stage evidence is retained encrypted; retries are a new attempt and
        // never mark an incomplete run as completed.
        val claimed = try {
            scheduler.requeueInterrupted(schedulerTaskId) { task ->
                runCatching { JSONObject(task.payload).optString("kind") == "background_council" }
                    .getOrDefault(false)
            }
            scheduler.claimSpecific(schedulerTaskId)
        } catch (error: Exception) {
            val message = "Main Brain could not safely claim the background council task: ${error.localizedMessage ?: error.javaClass.simpleName}"
            return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to message.take(1000)))
        }
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
        val budget = CouncilExecutionBudget(
            maxProviderCalls = payload?.optInt(BackgroundTaskQueue.KEY_MAX_PROVIDER_CALLS, 9) ?: 9,
            maxCallsPerProvider = payload?.optInt(BackgroundTaskQueue.KEY_MAX_CALLS_PER_PROVIDER, 9) ?: 9,
            maxDurationMs = payload?.optLong(BackgroundTaskQueue.KEY_MAX_DURATION_MS, 300_000L) ?: 300_000L,
            maxOutputCharactersPerStage = payload?.optInt(BackgroundTaskQueue.KEY_MAX_OUTPUT_CHARS, 9_000) ?: 9_000
        )
        if (goal.isBlank() || provider.isBlank()) {
            val message = "Encrypted background task payload is incomplete."
            scheduler.fail(schedulerTaskId, message)
            evidence.saveStatus(schedulerTaskId, "FAILED", message, attempt = attempt)
            return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to message))
        }

        val resumableRoles = buildSet {
            add("Planner")
            add("Researcher")
            repeat(specialistCount.coerceIn(1, 5)) { add("Specialist ${it + 1}") }
            add("Critic")
            add("Verifier")
        }
        val savedEvidence = try {
            evidence.readTask(schedulerTaskId).getOrThrow()
        } catch (error: Exception) {
            val message = "Could not read encrypted council checkpoints: ${error.localizedMessage ?: error.javaClass.simpleName}"
            runCatching { scheduler.fail(schedulerTaskId, message.take(500)) }
            return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to message.take(1000)))
        }
        val resumeStages = savedEvidence
            .filter { record ->
                record.optInt("schemaVersion") == 1 &&
                    record.optString("status") == "COMPLETED" &&
                    record.optString("goal") == goal &&
                    record.optString("role") in resumableRoles &&
                    record.optInt("attempt", -1) <= attempt
            }
            .groupBy { it.optString("role") }
            .mapNotNull { (role, records) ->
                val latest = records.maxWithOrNull(
                    compareBy<JSONObject> { it.optInt("attempt", -1) }
                        .thenBy { it.optLong("completedAt", 0L) }
                ) ?: return@mapNotNull null
                val savedProvider = latest.optString("provider").uppercase()
                val savedOutput = latest.optString("output")
                if (savedProvider.isBlank() || savedOutput.isBlank()) null
                else role to CouncilSavedStage(savedProvider, savedOutput)
            }.toMap()

        val runningStatus = evidence.saveStatus(
            schedulerTaskId,
            "RUNNING",
            "Attempt ${attempt + 1}: provider council started. Reusing ${resumeStages.size} securely saved, provider-matched stages.",
            attempt = attempt,
            providerUsage = "",
            elapsedMs = 0L
        )
        if (runningStatus.isFailure) {
            val message = "Could not persist council start status: ${runningStatus.exceptionOrNull()?.localizedMessage ?: "encrypted storage unavailable"}"
            runCatching { scheduler.fail(schedulerTaskId, message.take(500)) }
            return Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to message.take(1000)))
        }

        val result = try {
            withContext(Dispatchers.IO) {
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
                },
                    resumeStages = resumeStages,
                    budget = budget,
                    onProviderCall = { providerName, providerCalls, totalCalls, elapsed ->
                        synchronized(usageLock) {
                            usageCounts[providerName] = maxOf(usageCounts[providerName] ?: 0, providerCalls)
                            evidence.saveStatus(
                                schedulerTaskId,
                                "RUNNING",
                                "Attempt ${attempt + 1}: provider call $totalCalls started.",
                                attempt = attempt,
                                providerUsage = usageCounts.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" },
                                elapsedMs = elapsed
                            ).getOrThrow()
                        }
                    },
                    onTokenUsage = { usage ->
                        tokenUsageRecords.add(usage)
                    }
                )
            }
        } catch (cancelled: CancellationException) {
            runCatching { scheduler.cancel(schedulerTaskId) }
            runCatching {
                evidence.saveStatus(
                    schedulerTaskId,
                    "CANCELLED",
                    "WorkManager cancelled the worker. Completed stage evidence is retained; no final result is claimed.",
                    attempt = attempt
                )
            }
            throw cancelled
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

        return try {
            result.fold(
            onSuccess = { report ->
                val saved = evidence.saveStatus(
                    schedulerTaskId,
                    "COMPLETED",
                    "Planner, Researcher, Specialists, Critic and Verifier finished. Final answer saved.",
                    finalAnswer = report.finalAnswer,
                    attempt = attempt,
                    providerUsage = JSONObject()
                        .put("calls", report.providerUsage.joinToString(",") { "${it.provider}:${it.calls}" })
                        .put("tokens", JSONArray(tokenUsageRecords.map { usage ->
                            JSONObject()
                                .put("provider", usage.provider)
                                .put("model", usage.model)
                                .put("inputTokens", usage.inputTokens)
                                .put("outputTokens", usage.outputTokens)
                        })).toString(),
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
                    // WorkManager retries this worker with the same durable scheduler task.
                    // Mark the claim as retry-safe FAILED so the next invocation can move it
                    // back to PENDING through requeueInterrupted() before claimSpecific().
                    scheduler.fail(
                        schedulerTaskId,
                        "Process stopped during execution; action was not replayed. Provider-only council retry: $message"
                    )
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
        } catch (failure: Exception) {
            val message = "Background council could not persist its execution evidence: ${failure.localizedMessage ?: failure.javaClass.simpleName}".take(1000)
            // Do not leave a claimed task RUNNING when persistence itself fails.
            // The task is failed closed; a user can inspect partial evidence and start a new run.
            runCatching { scheduler.fail(schedulerTaskId, message.take(500)) }
            runCatching {
                evidence.saveStatus(
                    schedulerTaskId,
                    "FAILED",
                    message,
                    attempt = attempt
                )
            }
            Result.failure(workDataOf(BackgroundTaskQueue.KEY_ERROR to message))
        }
    }
}
