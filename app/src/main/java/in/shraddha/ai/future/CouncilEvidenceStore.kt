package `in`.shraddha.ai.future

import android.content.Context
import `in`.shraddha.ai.brain.EncryptedFeatureStore
import org.json.JSONObject

/**
 * Durable, device-local evidence for each background council stage.
 *
 * Provider outputs can contain user data, so records are written only to the existing
 * Android-Keystore-backed encrypted feature store. WorkManager Data contains only task IDs
 * and short status strings; it never carries raw prompts, provider outputs or API keys.
 */
class CouncilEvidenceStore(context: Context) {
    private val store = EncryptedFeatureStore(context.applicationContext)

    private fun taskToken(taskId: String): String {
        val safe = taskId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
        require(safe.isNotBlank()) { "A valid council task ID is required." }
        return safe
    }

    private fun prefix(taskId: String): String = "council_" + taskToken(taskId) + "_"

    fun saveStage(
        taskId: String,
        goal: String,
        role: String,
        provider: String,
        output: String,
        attempt: Int = 0,
        completedAt: Long = System.currentTimeMillis()
    ): Result<Unit> = runCatching {
        require(attempt in 0..99) { "Council attempt is out of range." }
        val roleToken = role.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(12)
        require(roleToken.isNotBlank()) { "Council role is missing." }
        require(output.isNotBlank() && output.length <= 9_000) { "Council stage output is empty or too large." }
        val record = JSONObject()
            .put("schemaVersion", 1)
            .put("taskId", taskToken(taskId))
            .put("goal", goal.take(4_000))
            .put("role", role.take(40))
            .put("provider", provider.uppercase().take(24))
            .put("output", output)
            .put("status", "COMPLETED")
            .put("completedAt", completedAt)
        store.put(prefix(taskId) + roleToken + "_a" + attempt, record.put("attempt", attempt).toString()).getOrThrow()
    }

    fun saveStatus(
        taskId: String,
        status: String,
        message: String,
        finalAnswer: String? = null,
        attempt: Int = 0,
        updatedAt: Long = System.currentTimeMillis()
    ): Result<Unit> = runCatching {
        require(attempt in 0..99) { "Council attempt is out of range." }
        val allowed = setOf("QUEUED", "RUNNING", "RETRY_PENDING", "COMPLETED", "FAILED", "CANCELLED")
        require(status in allowed) { "Unsupported council task status." }
        val record = JSONObject()
            .put("schemaVersion", 1)
            .put("taskId", taskToken(taskId))
            .put("status", status)
            .put("message", message.take(1_000))
            .put("attempt", attempt)
            .put("updatedAt", updatedAt)
        if (finalAnswer != null) record.put("finalAnswer", finalAnswer.take(9_000))
        store.put(prefix(taskId) + "status", record.toString()).getOrThrow()
    }

    fun readTask(taskId: String): Result<List<JSONObject>> = runCatching {
        store.getAllWithPrefixes(setOf(prefix(taskId))).getOrThrow()
            .toSortedMap()
            .values
            .mapNotNull { raw -> raw.takeIf { it.isNotBlank() }?.let(::JSONObject) }
    }

    fun removeTask(taskId: String): Boolean = store.removeAllWithPrefix(prefix(taskId))
}
