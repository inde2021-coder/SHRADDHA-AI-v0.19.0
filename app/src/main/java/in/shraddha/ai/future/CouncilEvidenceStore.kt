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
        providerUsage: String? = null,
        elapsedMs: Long? = null,
        updatedAt: Long = System.currentTimeMillis()
    ): Result<Unit> = runCatching {
        require(attempt in 0..99) { "Council attempt is out of range." }
        require(finalAnswer == null || finalAnswer.length <= MAX_FINAL_ANSWER_CHARS) {
            "Council final answer exceeds the evidence limit."
        }
        require(providerUsage == null || providerUsage.length <= MAX_PROVIDER_USAGE_CHARS) {
            "Council provider usage exceeds the evidence limit."
        }
        val allowed = setOf("QUEUED", "RUNNING", "RETRY_PENDING", "COMPLETED", "FAILED", "CANCELLED")
        require(status in allowed) { "Unsupported council task status." }
        val statusKey = prefix(taskId) + "status"
        // Do not turn an encrypted-store/Keystore read failure into an empty status:
        // doing so could overwrite previously persisted token usage or a final answer.
        val previousRaw = store.get(statusKey).getOrThrow()
        val previous = previousRaw
            .takeIf { it.isNotBlank() }
            ?.let { JSONObject(it) }
        val record = JSONObject()
            .put("schemaVersion", 1)
            .put("taskId", taskToken(taskId))
            .put("status", status)
            .put("message", message.take(1_000))
            .put("attempt", attempt)
            .put("updatedAt", updatedAt)
        val answer = finalAnswer ?: previous?.optString("finalAnswer")?.takeIf { it.isNotBlank() }
        val usage = providerUsage ?: previous?.optString("providerUsage")?.takeIf { it.isNotBlank() }
        val duration = elapsedMs ?: previous?.optLong("elapsedMs")?.takeIf { it > 0L }
        if (answer != null) record.put("finalAnswer", answer)
        if (usage != null) record.put("providerUsage", usage)
        if (duration != null) record.put("elapsedMs", duration.coerceAtLeast(0L))
        store.put(statusKey, record.toString()).getOrThrow()
    }

    fun readTask(taskId: String): Result<List<JSONObject>> = runCatching {
        store.getAllWithPrefixes(setOf(prefix(taskId))).getOrThrow()
            .toSortedMap()
            .values
            .mapNotNull { raw -> raw.takeIf { it.isNotBlank() }?.let(::JSONObject) }
    }

    fun removeTask(taskId: String): Boolean = store.removeAllWithPrefix(prefix(taskId))

    private companion object {
        const val MAX_FINAL_ANSWER_CHARS = 9_000
        // Enough for all nine bounded provider responses plus JSON metadata.
        const val MAX_PROVIDER_USAGE_CHARS = 6_000
    }
}
