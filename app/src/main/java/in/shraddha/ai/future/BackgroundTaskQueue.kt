package `in`.shraddha.ai.future

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

/** Persistent, network-aware queue for user-requested multi-agent work. */
object BackgroundTaskQueue {
    const val TAG = "shraddha-background-agent"
    const val KEY_GOAL = "goal"
    const val KEY_PROVIDER = "provider"
    const val KEY_LANGUAGE = "language"
    const val KEY_PROGRESS_ROLE = "progress_role"
    const val KEY_PROGRESS_INDEX = "progress_index"
    const val KEY_PROGRESS_TOTAL = "progress_total"
    const val KEY_FINAL_REPORT = "final_report"
    const val KEY_ERROR = "error"

    fun enqueue(context: Context, goal: String, provider: String, language: String = "AUTO"): UUID {
        val cleanGoal = goal.trim()
        require(cleanGoal.isNotBlank() && cleanGoal.length <= 2000) { "Task must contain 1-2000 characters" }
        require(provider.uppercase() in setOf("GEMINI", "OPENAI", "GROQ", "CLAUDE", "PERPLEXITY")) {
            "Unsupported AI provider"
        }
        val request = OneTimeWorkRequestBuilder<BackgroundAgentWorker>()
            .setInputData(workDataOf(
                KEY_GOAL to cleanGoal,
                KEY_PROVIDER to provider.uppercase(),
                KEY_LANGUAGE to language.take(20)
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "shraddha-agent-${request.id}", ExistingWorkPolicy.KEEP, request
        )
        return request.id
    }

    fun cancel(context: Context, id: UUID) {
        WorkManager.getInstance(context.applicationContext).cancelWorkById(id)
    }
}
